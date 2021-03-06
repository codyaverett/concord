package com.walmartlabs.concord.agent;

/*-
 * *****
 * Concord
 * -----
 * Copyright (C) 2017 - 2018 Walmart Inc.
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
import com.google.common.base.Charsets;
import com.google.common.hash.HashCode;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.walmartlabs.concord.agent.ProcessPool.ProcessEntry;
import com.walmartlabs.concord.common.IOUtils;
import com.walmartlabs.concord.dependencymanager.DependencyEntity;
import com.walmartlabs.concord.dependencymanager.DependencyManager;
import com.walmartlabs.concord.policyengine.CheckResult;
import com.walmartlabs.concord.policyengine.DependencyRule;
import com.walmartlabs.concord.policyengine.PolicyEngine;
import com.walmartlabs.concord.project.InternalConstants;
import com.walmartlabs.concord.sdk.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.walmartlabs.concord.common.DockerProcessBuilder.CONCORD_DOCKER_LOCAL_MODE_KEY;

public class DefaultJobExecutor {

    private static final Logger log = LoggerFactory.getLogger(DefaultJobExecutor.class);

    private final Configuration cfg;
    private final DefaultDependencies defaultDependencies;
    private final LogManager logManager;
    private final DependencyManager dependencyManager;
    private final ObjectMapper objectMapper;
    private final ProcessPool pool;
    private final ExecutorService executor;
    private final List<JobPostProcessor> postProcessors;

    public DefaultJobExecutor(Configuration cfg, LogManager logManager,
                              DependencyManager dependencyManager,
                              List<JobPostProcessor> postProcessors) {

        this.cfg = cfg;
        this.defaultDependencies = new DefaultDependencies();
        this.logManager = logManager;
        this.dependencyManager = dependencyManager;
        this.objectMapper = new ObjectMapper();
        this.pool = new ProcessPool(cfg.getMaxPreforkAge(), cfg.getMaxPreforkCount());
        this.executor = Executors.newCachedThreadPool();
        this.postProcessors = postProcessors;
    }

    public JobInstance start(UUID instanceId, Path workDir) {
        try {
            Job job = new Job(instanceId, workDir, readCfg(workDir));

            Collection<Path> resolvedDeps = resolveDeps(job);

            ProcessEntry entry;
            if (canUsePrefork(job)) {
                String[] cmd = createCmd(job, resolvedDeps, null);
                entry = fork(job, cmd);
            } else {
                log.info("start ['{}'] -> can't use a pre-forked instance", instanceId);
                Path procDir = IOUtils.createTempDir("onetime");
                String[] cmd = createCmd(job, resolvedDeps, procDir);
                entry = startOneTime(job, cmd, procDir);
            }

            Path payloadDir = entry.getWorkDir().resolve(InternalConstants.Files.PAYLOAD_DIR_NAME);

            Process proc = entry.getProcess();
            CompletableFuture<?> f = CompletableFuture.supplyAsync(() -> exec(instanceId, entry.getWorkDir(), proc, payloadDir), executor);
            return createJobInstance(instanceId, workDir, proc, f);
        } catch (Exception e) {
            log.warn("start ['{}', '{}'] -> process startup error: {}", instanceId, workDir, e.getMessage());
            logManager.log(instanceId, "Process startup error: %s", e);

            CompletableFuture<?> f = new CompletableFuture<>();
            f.completeExceptionally(e);

            return createJobInstance(instanceId, workDir, null, f);
        }
    }

    private void logDependencies(Job job, Collection<?> deps) {
        if (deps == null || deps.isEmpty()) {
            logManager.log(job.instanceId, "No external dependencies.");
            return;
        }

        List<String> l = deps.stream()
                .map(Object::toString)
                .collect(Collectors.toList());

        StringBuilder b = new StringBuilder();
        for (String s : l) {
            b.append("\n\t").append(s);
        }

        logManager.log(job.instanceId, "Dependencies: %s", b);
    }

    private Collection<Path> resolveDeps(Job job) throws IOException, ExecutionException {
        long t1 = System.currentTimeMillis();

        Collection<URI> uris = Stream.concat(defaultDependencies.getDependencies().stream(), getDependencyUris(job).stream())
                .collect(Collectors.toList());

        Collection<DependencyEntity> deps = dependencyManager.resolve(uris);

        checkDependencies(job, deps);

        Collection<Path> paths = deps.stream()
                .map(DependencyEntity::getPath)
                .sorted()
                .collect(Collectors.toList());

        long t2 = System.currentTimeMillis();

        if (job.debugMode) {
            logManager.log(job.instanceId, "Dependency resolution took %dms", (t2 - t1));
            logDependencies(job, paths);
        } else {
            logDependencies(job, uris);
        }

        return paths;
    }

    private String[] createCmd(Job job, Collection<Path> deps, Path procDir) throws IOException, ExecutionException {
        Map<String, Object> containerCfg = getContainerCfg(job);
        boolean withContainer = !containerCfg.isEmpty();

        String javaCmd = withContainer ? DockerCommandBuilder.getJavaCmd() : cfg.getAgentJavaCmd();
        Path depsFile = storeDeps(deps);
        if (withContainer) {
            depsFile = DockerCommandBuilder.getDependencyListsDir().resolve(depsFile.getFileName());
        }
        Path runnerPath = withContainer ? DockerCommandBuilder.getRunnerPath(cfg.getRunnerPath()) : cfg.getRunnerPath().toAbsolutePath();
        Path runnerDir = withContainer ? DockerCommandBuilder.getWorkspaceDir().resolve(InternalConstants.Files.PAYLOAD_DIR_NAME) : null;

        RunnerCommandBuilder runner = new RunnerCommandBuilder()
                .javaCmd(javaCmd)
                .workDir(job.workDir)
                .procDir(runnerDir)
                .agentId(cfg.getAgentId())
                .serverApiBaseUrl(cfg.getServerApiBaseUrl())
                .securityManagerEnabled(cfg.isRunnerSecurityManagerEnabled())
                .dependencies(depsFile)
                .debug(job.debugMode)
                .runnerPath(runnerPath);

        if (!withContainer) {
            return runner.build();
        }

        return new DockerCommandBuilder(logManager, cfg.getJavaPath(), containerCfg)
                .procDir(procDir)
                .instanceId(job.instanceId)
                .dependencyListsDir(cfg.getDependencyListsDir())
                .dependencyCacheDir(cfg.getDependencyCacheDir())
                .dependencyDir(dependencyManager.getLocalCacheDir())
                .runnerPath(cfg.getRunnerPath())
                .args(runner.build())
                .extraEnv(IOUtils.TMP_DIR_KEY, "/tmp")
                .extraEnv("DOCKER_HOST", cfg.getDockerHost())
                .build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readCfg(Path workDir) throws ExecutionException {
        Path p = workDir.resolve(InternalConstants.Files.REQUEST_DATA_FILE_NAME);
        if (!Files.exists(p)) {
            return Collections.emptyMap();
        }

        try (InputStream in = Files.newInputStream(p)) {
            return objectMapper.readValue(in, Map.class);
        } catch (IOException e) {
            throw new ExecutionException("Error while reading process configuration", e);
        }
    }

    private Path storeDeps(Collection<Path> dependencies) throws IOException {
        List<String> deps = dependencies.stream()
                .map(p -> p.toAbsolutePath().toString())
                .collect(Collectors.toList());

        HashCode depsHash = hash(deps.toArray(new String[0]));

        Path result = cfg.getDependencyListsDir().resolve(depsHash.toString() + ".deps");
        if (Files.exists(result)) {
            return result;
        }

        Files.write(result,
                (Iterable<String>) deps.stream()::iterator,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        return result;
    }

    private void checkDependencies(Job job, Collection<DependencyEntity> resolvedDepEntities) throws IOException {
        Map<String, Object> policyRules = readPolicyRules(job);
        if (policyRules.isEmpty()) {
            return;
        }

        logManager.info(job.instanceId, "Checking the dependency policy");

        CheckResult<DependencyRule, DependencyEntity> result = new PolicyEngine(policyRules).getDependencyPolicy().check(resolvedDepEntities);
        result.getWarn().forEach(d -> {
            logManager.warn(job.instanceId, "Potentially restricted artifact '{}' (dependency policy: {})", d.getEntity().toString(), d.getRule().toString());
        });
        result.getDeny().forEach(d -> {
            logManager.error(job.instanceId, "Artifact '{}' is forbidden by the dependency policy {}", d.getEntity().toString(), d.getRule().toString());
        });

        if (!result.getDeny().isEmpty()) {
            throw new RuntimeException("Found forbidden dependencies");
        }
    }

    private Integer exec(UUID instanceId, Path workDir, Process proc, Path payloadDir) {
        try {
            try {
                logManager.log(instanceId, proc.getInputStream());
            } catch (IOException e) {
                log.warn("start ['{}', '{}'] -> error while saving a log file: {}", instanceId, workDir, e.getMessage());
            }

            int code;
            try {
                code = proc.waitFor();
            } catch (Exception e) {
                handleError(instanceId, workDir, proc, e.getMessage());
                throw new JobExecutorException("Error while executing a job: " + e.getMessage());
            }

            if (code != 0) {
                log.warn("exec ['{}'] -> finished with {}", instanceId, code);
                handleError(instanceId, workDir, proc, "Process exit code: " + code);
                throw new JobExecutorException("Error while executing a job, process exit code: " + code);
            }

            log.info("exec ['{}'] -> finished with {}", instanceId, code);
            logManager.log(instanceId, "Process finished with: %s", code);

            return code;
        } finally {
            try {
                for (JobPostProcessor p : postProcessors) {
                    p.process(instanceId, payloadDir);
                }
            } catch (ExecutionException e) {
                log.warn("exec ['{}'] -> postprocessing error: {}", instanceId, e.getMessage());
                handleError(instanceId, workDir, proc, e.getMessage());
            }

            try {
                log.info("exec ['{}'] -> removing the working directory: {}", instanceId, workDir);
                IOUtils.deleteRecursively(workDir);
            } catch (IOException e) {
                log.warn("exec ['{}'] -> can't remove the working directory: {}", instanceId, e.getMessage());
            }
        }
    }

    private ProcessEntry fork(Job job, String[] cmd) throws ExecutionException {
        long t1 = System.currentTimeMillis();

        HashCode hc = hash(cmd);

        ProcessEntry entry = pool.take(hc, () -> {
            Path forkDir = IOUtils.createTempDir("prefork");
            return start(forkDir, cmd);
        });

        try {
            Path payloadDir = entry.getWorkDir().resolve(InternalConstants.Files.PAYLOAD_DIR_NAME);
            // TODO use move
            IOUtils.copy(job.workDir, payloadDir);
            writeInstanceId(job.instanceId, payloadDir);
        } catch (IOException e) {
            throw new ExecutionException("Error while starting a process", e);
        }

        long t2 = System.currentTimeMillis();

        if (job.debugMode) {
            logManager.log(job.instanceId, "Forking a VM took %dms", (t2 - t1));
        }

        return entry;
    }

    private JobInstance createJobInstance(UUID instanceId, Path workDir, Process proc, CompletableFuture<?> f) {
        return new JobInstance() {

            @Override
            public UUID instanceId() {
                return instanceId;
            }

            @Override
            public Path workDir() {
                return workDir;
            }

            @Override
            public Path logFile() {
                return logManager.open(instanceId);
            }

            @Override
            public void cancel() {
                if (!f.isCancelled()) {
                    f.cancel(true);
                }

                if (proc != null && Utils.kill(proc)) {
                    log.warn("kill -> killed by user: {}", instanceId);
                    logManager.log(instanceId, "Killed by user");
                }
            }

            @Override
            public CompletableFuture<?> future() {
                return f;
            }
        };
    }

    private ProcessEntry startOneTime(Job job, String[] cmd, Path procDir) throws IOException {
        Path payloadDir = procDir.resolve(InternalConstants.Files.PAYLOAD_DIR_NAME);
        Files.move(job.workDir, payloadDir, StandardCopyOption.ATOMIC_MOVE);
        writeInstanceId(job.instanceId, payloadDir);

        return start(procDir, cmd);
    }

    private ProcessEntry start(Path procDir, String[] cmd) throws IOException {
        Path payloadDir = procDir.resolve(InternalConstants.Files.PAYLOAD_DIR_NAME);
        if (!Files.exists(payloadDir)) {
            Files.createDirectories(payloadDir);
        }

        log.info("start -> {}, {}", payloadDir, String.join(" ", cmd));

        ProcessBuilder b = new ProcessBuilder()
                .directory(payloadDir.toFile())
                .command(cmd)
                .redirectErrorStream(true);

        // TODO constants
        Map<String, String> env = b.environment();
        env.put(IOUtils.TMP_DIR_KEY, IOUtils.TMP_DIR.toAbsolutePath().toString());
        env.put("_CONCORD_ATTACHMENTS_DIR", payloadDir.resolve(InternalConstants.Files.JOB_ATTACHMENTS_DIR_NAME)
                .toAbsolutePath().toString());

        // pass through the docker mode
        String dockerMode = System.getenv(CONCORD_DOCKER_LOCAL_MODE_KEY);
        if (dockerMode != null) {
            log.debug("start -> using Docker mode: {}", dockerMode);
            env.put(CONCORD_DOCKER_LOCAL_MODE_KEY, dockerMode);
        }

        Process p = b.start();
        return new ProcessEntry(p, procDir);
    }

    private void handleError(UUID id, Path workDir, Process proc, String error) {
        log.warn("handleError ['{}', '{}'] -> execution error: {}", id, workDir, error);
        logManager.log(id, "Error: " + error);

        if (Utils.kill(proc)) {
            log.warn("handleError ['{}', '{}'] -> killed by agent", id, workDir);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readPolicyRules(Job job) throws IOException {
        Path policyFile = job.workDir.resolve(InternalConstants.Files.CONCORD_SYSTEM_DIR_NAME)
                .resolve(InternalConstants.Files.POLICY_FILE_NAME);

        if (!Files.exists(policyFile)) {
            return Collections.emptyMap();
        }

        return objectMapper.readValue(policyFile.toFile(), Map.class);
    }

    @SuppressWarnings("unchecked")
    private static Collection<URI> getDependencyUris(Job j) throws ExecutionException {
        try {
            Map<String, Object> m = j.cfg;
            Collection<String> deps = (Collection<String>) m.get(InternalConstants.Request.DEPENDENCIES_KEY);
            return normalizeUrls(deps);
        } catch (URISyntaxException | IOException e) {
            throw new ExecutionException("Error while reading the list of dependencies", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> getContainerCfg(Job job) {
        return (Map<String, Object>) job.cfg.getOrDefault(InternalConstants.Request.CONTAINER, Collections.emptyMap());
    }

    private static Collection<URI> normalizeUrls(Collection<String> urls) throws IOException, URISyntaxException {
        if (urls == null || urls.isEmpty()) {
            return Collections.emptySet();
        }

        Collection<URI> result = new HashSet<>();

        for (String s : urls) {
            URI u = new URI(s);
            String scheme = u.getScheme();

            if (DependencyManager.MAVEN_SCHEME.equalsIgnoreCase(scheme)) {
                result.add(u);
                continue;
            }

            if (scheme == null || scheme.trim().isEmpty()) {
                throw new IOException("Invalid dependency URL. Missing URL scheme: " + s);
            }

            if (s.endsWith(".jar")) {
                result.add(u);
                continue;
            }

            URL url = u.toURL();
            while (true) {
                if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                    URLConnection conn = url.openConnection();
                    if (conn instanceof HttpURLConnection) {
                        HttpURLConnection httpConn = (HttpURLConnection) conn;
                        httpConn.setInstanceFollowRedirects(false);

                        int code = httpConn.getResponseCode();
                        if (code == HttpURLConnection.HTTP_MOVED_TEMP ||
                                code == HttpURLConnection.HTTP_MOVED_PERM ||
                                code == HttpURLConnection.HTTP_SEE_OTHER ||
                                code == 307) {

                            String location = httpConn.getHeaderField("Location");
                            url = new URL(location);
                            log.info("normalizeUrls -> using: {}", location);

                            continue;
                        }

                        u = url.toURI();
                    } else {
                        log.warn("normalizeUrls -> unexpected connection type: {} (for {})", conn.getClass(), s);
                    }
                }

                break;
            }

            result.add(u);
        }

        return result;
    }

    private static boolean canUsePrefork(Job job) {
        if (!getContainerCfg(job).isEmpty()) {
            return false;
        }

        if (Files.exists(job.workDir.resolve(InternalConstants.Files.LIBRARIES_DIR_NAME))) {
            // payload supplied its own libraries
            return false;
        }

        return !Files.exists(job.workDir.resolve(InternalConstants.Agent.AGENT_PARAMS_FILE_NAME));
    }

    private static void writeInstanceId(UUID instanceId, Path dst) throws IOException {
        Path idPath = dst.resolve(InternalConstants.Files.INSTANCE_ID_FILE_NAME);
        Files.write(idPath, instanceId.toString().getBytes(), StandardOpenOption.CREATE, StandardOpenOption.SYNC);
    }

    private static HashCode hash(String[] as) {
        HashFunction f = Hashing.sha256();
        Hasher h = f.newHasher();
        for (String s : as) {
            h.putString(s, Charsets.UTF_8);
        }
        return h.hash();
    }

    private static class Job {

        private final UUID instanceId;
        private final Path workDir;
        private final Map<String, Object> cfg;
        private final boolean debugMode;

        private Job(UUID instanceId, Path workDir, Map<String, Object> cfg) {
            this.instanceId = instanceId;
            this.workDir = workDir;
            this.cfg = cfg;

            this.debugMode = debugMode(this);
        }

        private static boolean debugMode(Job job) {
            Object v = job.cfg.get(Constants.Request.DEBUG_KEY);
            if (v instanceof String) {
                // allows `curl ... -F debug=true`
                return Boolean.parseBoolean((String) v);
            }

            return Boolean.TRUE.equals(v);
        }
    }
}
