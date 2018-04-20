package com.walmartlabs.concord.client;

/*-
 * *****
 * Concord
 * -----
 * Copyright (C) 2017 Wal-Mart Store, Inc.
 * -----
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * =====
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walmartlabs.concord.common.IOUtils;
import com.walmartlabs.concord.sdk.Constants;
import com.walmartlabs.concord.sdk.Context;
import com.walmartlabs.concord.sdk.InjectVariable;
import com.walmartlabs.concord.server.api.process.ProcessEntry;
import com.walmartlabs.concord.server.api.process.ProcessResource;
import com.walmartlabs.concord.server.api.process.ProcessStatus;
import com.walmartlabs.concord.server.api.process.StartProcessResponse;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Named;
import javax.ws.rs.core.Response;
import javax.xml.bind.DatatypeConverter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import static com.walmartlabs.concord.client.Keys.ACTION_KEY;

@Named("concord")
public class ConcordTask extends AbstractConcordTask {

    private static final Logger log = LoggerFactory.getLogger(ConcordTask.class);

    private static final long DEFAULT_KILL_TIMEOUT = 10000;
    private static final long DEFAULT_POLL_DELAY = 5000;

    /**
     * @deprecated use {@link #PAYLOAD_KEY}
     */
    @Deprecated
    private static final String ARCHIVE_KEY = "archive";

    /**
     * @deprecated use {@link #REPO_KEY}
     */
    @Deprecated
    private static final String REPOSITORY_KEY = "repository";

    private static final String PAYLOAD_KEY = "payload";
    private static final String ORG_KEY = "org";
    private static final String PROJECT_KEY = "project";
    private static final String REPO_KEY = "repo";
    private static final String SYNC_KEY = "sync";
    private static final String ENTRY_POINT_KEY = "entryPoint";
    private static final String ARGUMENTS_KEY = "arguments";
    private static final String OUT_VARS_KEY = "outVars";
    private static final String JOBS_KEY = "jobs";
    private static final String INSTANCES_KEY = "instances";
    private static final String INSTANCE_ID_KEY = "instanceId";
    private static final String TAGS_KEY = "tags";
    private static final String START_AT_KEY = "startAt";
    private static final String DISABLE_ON_CANCEL_KEY = "disableOnCancel";
    private static final String DISABLE_ON_FAILURE_KEY = "disableOnFailure";
    private static final String JOB_OUT_KEY = "jobOut";

    @InjectVariable("concord")
    Map<String, Object> defaults;

    @InjectVariable("projectInfo")
    Map<String, Object> projectInfo;

    @Override
    public void execute(Context ctx) throws Exception {
        Action action = getAction(ctx);
        switch (action) {
            case START: {
                String instanceId = (String) ctx.getVariable(Constants.Context.TX_ID_KEY);
                start(ctx, instanceId);
                break;
            }
            case FORK: {
                fork(ctx);
                break;
            }
            case KILL: {
                kill(ctx);
                break;
            }
            default:
                throw new IllegalArgumentException("Unsupported action type: " + action);
        }
    }

    public List<String> listSubprocesses(@InjectVariable("context") Context ctx, String instanceId, String... tags) throws Exception {
        Map<String, Object> m = new HashMap<>();
        m.put(INSTANCE_ID_KEY, instanceId);
        if (tags != null) {
            m.put(TAGS_KEY, new HashSet<>(Arrays.asList(tags)));
        }
        return listSubprocesses(ctx, createJobCfg(ctx, m));
    }

    @SuppressWarnings("unchecked")
    public List<String> listSubprocesses(@InjectVariable("context") Context ctx, Map<String, Object> cfg) throws Exception {
        UUID instanceId = UUID.fromString(get(cfg, INSTANCE_ID_KEY));
        Set<String> tags = getTags(cfg);

        return withClient(ctx, target -> {
            ProcessResource proxy = target.proxy(ProcessResource.class);
            List<ProcessEntry> result = proxy.list(instanceId, tags);
            return result.stream()
                    .map(ProcessEntry::getInstanceId)
                    .map(UUID::toString)
                    .collect(Collectors.toList());
        });
    }

    public Map<String, Object> waitForCompletion(@InjectVariable("context") Context ctx, List<String> ids) throws Exception {
        return waitForCompletion(ctx, ids, -1);
    }

    public Map<String, Object> waitForCompletion(@InjectVariable("context") Context ctx, List<String> ids, long timeout) throws Exception {
        Map<String, Object> result = new HashMap<>();

        ids.parallelStream().map(UUID::fromString).forEach(id -> {
            log.info("Waiting for {}...", id);

            long t1 = System.currentTimeMillis();
            while (true) {
                try {
                    ProcessStatus s = withClient(ctx, target -> {
                        ProcessResource proxy = target.proxy(ProcessResource.class);
                        ProcessEntry e = proxy.get(id);
                        return e.getStatus();
                    });

                    if (s == ProcessStatus.FAILED || s == ProcessStatus.FINISHED || s == ProcessStatus.CANCELLED) {
                        result.put(id.toString(), s.name());
                        break;
                    } else {
                        long t2 = System.currentTimeMillis();
                        if (timeout > 0) {
                            long dt = t2 - t1;
                            if (dt >= timeout) {
                                throw new TimeoutException("Timeout waiting for " + id + ": " + dt);
                            }
                        }

                        Thread.sleep(DEFAULT_POLL_DELAY);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });

        return result;
    }

    private void start(Context ctx, String instanceId) throws Exception {
        Map<String, Object> cfg = createJobCfg(ctx, defaults);

        String org = (String) cfg.get(ORG_KEY);
        if (org == null) {
            org = (String) projectInfo.get("orgName");
        }

        String project = (String) cfg.get(PROJECT_KEY);

        String repo = (String) cfg.get(REPO_KEY);
        if (repo == null) {
            repo = (String) cfg.get(REPOSITORY_KEY);
        }

        Map<String, Object> req = createRequest(cfg);
        boolean sync = (boolean) cfg.getOrDefault(SYNC_KEY, false);

        Path workDir = Paths.get((String) ctx.getVariable(Constants.Context.WORK_DIR_KEY));
        Path archive = archivePayload(workDir, cfg);

        if (project == null && archive == null) {
            throw new IllegalArgumentException("'" + PAYLOAD_KEY + "' and/or '" + PROJECT_KEY + "' are required");
        }

        log.info("Starting a child process (project={}, repository={}, archive={}, sync={}, req={})",
                project, repo, archive, sync, req);

        String targetUri = ProcessResource.class.getAnnotation(javax.ws.rs.Path.class).value();

        Map<String, Object> input = new HashMap<>();

        if (archive != null) {
            input.put("archive", Files.readAllBytes(archive));
        }

        ObjectMapper om = new ObjectMapper();
        input.put("request", om.writeValueAsBytes(req));

        if (org != null) {
            input.put("org", org);
        }

        if (project != null) {
            input.put("project", project);
        }

        if (repo != null) {
            input.put("repo", repo);
        }

        String startAt = getStartAt(cfg);
        if (startAt != null) {
            input.put("startAt", startAt);
        }

        input.put("parentInstanceId", instanceId);

        StartProcessResponse resp = request(ctx, targetUri, input, StartProcessResponse.class);

        String childId = resp.getInstanceId().toString();
        log.info(sync ? "Child process completed: {}" : "Started a child process: {}", childId);

        List<String> jobs = Collections.singletonList(childId);
        ctx.setVariable(JOBS_KEY, jobs);

        if (sync) {
            waitForCompletion(ctx, jobs);

            Object out = withClient(ctx, target -> {
                ProcessResource processResource = target.proxy(ProcessResource.class);
                Response r = processResource.downloadAttachment(UUID.fromString(childId), "out.json");
                if (r.getStatus() == 200) {
                    String s = r.readEntity(String.class);
                    return om.readValue(s, Map.class);
                }
                return null;
            });

            ctx.setVariable(JOB_OUT_KEY, out != null ? out : Collections.emptyMap());
        }
    }

    @SuppressWarnings("unchecked")
    private void fork(Context ctx) throws Exception {
        List<Map<String, Object>> jobs;

        Object v = ctx.getVariable(JOBS_KEY);
        if (v != null) {
            if (v instanceof List) {
                jobs = (List<Map<String, Object>>) v;
            } else {
                throw new IllegalArgumentException("'" + JOBS_KEY + "' must be a list");
            }
        } else {
            jobs = Collections.singletonList(createJobCfg(ctx, null));
        }

        List<String> jobIds = forkMany(ctx, jobs);
        ctx.setVariable(JOBS_KEY, jobIds);
    }

    private List<String> forkMany(Context ctx, List<Map<String, Object>> jobs) throws Exception {
        if (jobs.isEmpty()) {
            throw new IllegalArgumentException("'" + JOBS_KEY + "' can't be an empty list");
        }

        List<String> ids = new ArrayList<>();

        for (Map<String, Object> job : jobs) {
            Map<String, Object> cfg = createJobCfg(ctx, job);
            cfg.put(INSTANCE_ID_KEY, ctx.getVariable(Constants.Context.TX_ID_KEY));

            int n = getInstances(cfg);
            for (int i = 0; i < n; i++) {
                UUID id = forkOne(ctx, cfg);
                ids.add(id.toString());
            }
        }

        return ids;
    }

    private UUID forkOne(Context ctx, Map<String, Object> cfg) throws Exception {
        if (cfg.containsKey(ARCHIVE_KEY)) {
            log.warn("'" + ARCHIVE_KEY + "' parameter is not supported for fork action and will be ignored");
        }

        UUID instanceId = UUID.fromString(get(cfg, INSTANCE_ID_KEY));
        boolean sync = (boolean) cfg.getOrDefault(SYNC_KEY, false);

        Map<String, Object> req = createRequest(cfg);

        log.info("Forking the current instance (sync={}, req={})...", sync, req);

        return withClient(ctx, target -> {
            ProcessResource proxy = target.proxy(ProcessResource.class);
            StartProcessResponse resp = proxy.fork(instanceId, req, sync, null);
            log.info("Forked a child process: {}", resp.getInstanceId());
            return resp.getInstanceId();
        });
    }

    private void kill(Context ctx) throws Exception {
        Map<String, Object> cfg = createCfg(ctx);
        kill(ctx, cfg);
    }

    public void kill(@InjectVariable("context") Context ctx, Map<String, Object> cfg) throws Exception {
        List<String> ids = new ArrayList<>();

        Object v = cfg.get(INSTANCE_ID_KEY);
        if (v instanceof String || v instanceof UUID) {
            ids.add(v.toString());
        } else if (v instanceof String[] || v instanceof UUID[]) {
            Object[] os = (Object[]) v;
            for (Object o : os) {
                ids.add(o.toString());
            }
        } else if (v instanceof Collection) {
            for (Object o : (Collection) v) {
                if (o instanceof String || o instanceof UUID) {
                    ids.add(o.toString());
                } else {
                    throw new IllegalArgumentException("'" + INSTANCE_ID_KEY + "' value should be a string or an UUID: " + o);
                }
            }
        } else {
            throw new IllegalArgumentException("'" + INSTANCE_ID_KEY + "' should be a single string, an UUID value or an array of strings or UUIDs: " + v);
        }

        killMany(ctx, cfg, ids);
    }

    private void killMany(Context ctx, Map<String, Object> cfg, List<String> instanceIds) throws Exception {
        if (instanceIds == null || instanceIds.isEmpty()) {
            throw new IllegalArgumentException("'" + INSTANCE_ID_KEY + "' should be a single value or an array of values: " + instanceIds);
        }

        for (String id : instanceIds) {
            killOne(ctx, cfg, id);
        }
    }

    private void killOne(Context ctx, Map<String, Object> cfg, String instanceId) throws Exception {
        withClient(ctx, target -> {
            ProcessResource proxy = target.proxy(ProcessResource.class);
            proxy.kill(UUID.fromString(instanceId));
            return null;
        });

        boolean sync = (boolean) cfg.getOrDefault(SYNC_KEY, false);
        if (sync) {
            waitForCompletion(ctx, Collections.singletonList(instanceId), DEFAULT_KILL_TIMEOUT);
        }
    }

    private Map<String, Object> createJobCfg(Context ctx, Map<String, Object> job) {
        Map<String, Object> m = createCfg(ctx, SYNC_KEY, ENTRY_POINT_KEY, PAYLOAD_KEY, ARCHIVE_KEY, ORG_KEY, PROJECT_KEY,
                REPO_KEY, REPOSITORY_KEY, ARGUMENTS_KEY, INSTANCE_ID_KEY, TAGS_KEY, START_AT_KEY, DISABLE_ON_CANCEL_KEY,
                DISABLE_ON_FAILURE_KEY, OUT_VARS_KEY);

        if (job != null) {
            m.putAll(job);
        }

        return m;
    }

    private static Path archivePayload(Path workDir, Map<String, Object> cfg) throws IOException {
        String s = (String) cfg.get(PAYLOAD_KEY);
        if (s == null) {
            s = (String) cfg.get(ARCHIVE_KEY);
        }

        if (s == null) {
            return null;
        }

        Path path = workDir.resolve(s);
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("File or directory not found: " + path);
        }

        if (Files.isDirectory(path)) {
            Path tmp = IOUtils.createTempFile("payload", ".zip");
            try (ZipArchiveOutputStream out = new ZipArchiveOutputStream(Files.newOutputStream(tmp))) {
                IOUtils.zip(out, path);
            }
            return tmp;
        }

        return path;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> createRequest(Map<String, Object> cfg) {
        Map<String, Object> req = new HashMap<>();

        String entryPoint = (String) cfg.get(ENTRY_POINT_KEY);
        if (entryPoint != null) {
            req.put(Constants.Request.ENTRY_POINT_KEY, entryPoint);
        }

        Set<String> tags = getTags(cfg);
        if (tags != null) {
            req.put(Constants.Request.TAGS_KEY, tags);
        }

        Map<String, Object> args = (Map<String, Object>) cfg.get(ARGUMENTS_KEY);
        if (args != null) {
            req.put(Constants.Request.ARGUMENTS_KEY, new HashMap<>(args));
        }

        if (getBoolean(cfg, DISABLE_ON_CANCEL_KEY)) {
            req.put(Constants.Request.DISABLE_ON_CANCEL_KEY, true);
        }

        if (getBoolean(cfg, DISABLE_ON_FAILURE_KEY)) {
            req.put(Constants.Request.DISABLE_ON_FAILURE_KEY, true);
        }

        Collection<String> outVars = (Collection<String>) cfg.get(OUT_VARS_KEY);
        if (outVars != null && !outVars.isEmpty()) {
            req.put(Constants.Request.OUT_EXPRESSIONS_KEY, outVars);
        }

        return req;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> getTags(Map<String, Object> cfg) {
        if (cfg == null) {
            return null;
        }

        Object v = cfg.get(TAGS_KEY);
        if (v == null) {
            return null;
        }

        if (v instanceof String) {
            return Collections.singleton((String) v);
        } else if (v instanceof String[]) {
            return new HashSet<>(Arrays.asList((String[]) v));
        } else if (v instanceof Collection) {
            return new HashSet<>((Collection) v);
        } else {
            throw new IllegalArgumentException("'" + TAGS_KEY + "' must a single string value or an array of strings: " + v);
        }
    }

    private static boolean getBoolean(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v instanceof Boolean) {
            return (Boolean) v;
        } else if (v instanceof String) {
            return Boolean.parseBoolean((String) v);
        }
        return false;
    }

    private static Action getAction(Context ctx) {
        Object v = ctx.getVariable(ACTION_KEY);
        if (v instanceof String) {
            String s = (String) v;
            return Action.valueOf(s.trim().toUpperCase());
        }
        throw new IllegalArgumentException("'" + ACTION_KEY + "' must be a string");
    }

    private static int getInstances(Map<String, Object> cfg) {
        int i;
        
        Object v = cfg.getOrDefault(INSTANCES_KEY, 1);
        if (v instanceof Integer) {
            i = (Integer) v;
        } else if (v instanceof Long) {
            i = ((Long) v).intValue();
        } else {
            throw new IllegalArgumentException("'" + INSTANCES_KEY + "' must be a number");
        }

        if (i <= 0) {
            throw new IllegalArgumentException("'" + INSTANCES_KEY + "' must be a positive number");
        }

        return i;
    }

    private static String getStartAt(Map<String, Object> cfg) {
        Object v = cfg.get(START_AT_KEY);
        if (v == null) {
            return null;
        }

        if (v instanceof String) {
            return (String) v;
        } else if (v instanceof Date) {
            Calendar c = Calendar.getInstance();
            c.setTime((Date) v);
            return DatatypeConverter.printDateTime(c);
        } else if (v instanceof Calendar) {
            return DatatypeConverter.printDateTime((Calendar) v);
        } else {
            throw new IllegalArgumentException("'" + START_AT_KEY + "' must be a string, java.util.Date or java.util.Calendar value. Got: " + v);
        }
    }

    private enum Action {

        START,
        FORK,
        KILL
    }
}
