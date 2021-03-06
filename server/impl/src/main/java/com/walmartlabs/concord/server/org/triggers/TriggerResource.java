package com.walmartlabs.concord.server.org.triggers;

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

import com.walmartlabs.concord.common.validation.ConcordKey;
import com.walmartlabs.concord.db.AbstractDao;
import com.walmartlabs.concord.project.ProjectLoader;
import com.walmartlabs.concord.project.model.ProjectDefinition;
import com.walmartlabs.concord.repository.Repository;
import com.walmartlabs.concord.server.ConcordApplicationException;
import com.walmartlabs.concord.server.org.OrganizationEntry;
import com.walmartlabs.concord.server.org.OrganizationManager;
import com.walmartlabs.concord.server.org.ResourceAccessLevel;
import com.walmartlabs.concord.server.org.project.*;
import com.walmartlabs.concord.server.repository.RepositoryManager;
import com.walmartlabs.concord.server.security.UserPrincipal;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.Authorization;
import org.apache.shiro.authz.UnauthorizedException;
import org.jooq.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonatype.siesta.Resource;
import org.sonatype.siesta.ValidationErrorsException;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.walmartlabs.concord.server.org.project.RepositoryUtils.assertRepository;

@Named
@Singleton
@Api(value = "Triggers", authorizations = {@Authorization("api_key"), @Authorization("session_key"), @Authorization("ldap")})
@javax.ws.rs.Path("/api/v1/org")
public class TriggerResource extends AbstractDao implements Resource {

    private static final Logger log = LoggerFactory.getLogger(TriggerResource.class);

    private final ProjectLoader projectLoader = new ProjectLoader();
    private final RepositoryDao repositoryDao;
    private final TriggersDao triggersDao;
    private final RepositoryManager repositoryManager;
    private final ProjectAccessManager projectAccessManager;
    private final OrganizationManager orgManager;
    private final Map<String, TriggerProcessor> triggerProcessors;

    @Inject
    public TriggerResource(@Named("app") Configuration cfg,
                           RepositoryDao repositoryDao,
                           TriggersDao triggersDao,
                           RepositoryManager repositoryManager,
                           ProjectAccessManager projectAccessManager,
                           OrganizationManager orgManager,
                           Map<String, TriggerProcessor> triggerProcessors) {

        super(cfg);
        this.repositoryDao = repositoryDao;
        this.triggersDao = triggersDao;
        this.repositoryManager = repositoryManager;
        this.projectAccessManager = projectAccessManager;
        this.orgManager = orgManager;
        this.triggerProcessors = triggerProcessors;
    }

    /**
     * List process trigger definitions for the specified project and repository.
     *
     * @param projectName
     * @param repositoryName
     * @return
     */
    @GET
    @ApiOperation(value = "List trigger definitions", responseContainer = "list", response = TriggerEntry.class)
    @javax.ws.rs.Path("/{orgName}/project/{projectName}/repo/{repositoryName}/trigger")
    @Produces(MediaType.APPLICATION_JSON)
    public List<TriggerEntry> list(@ApiParam @PathParam("orgName") @ConcordKey String orgName,
                                   @ApiParam @PathParam("projectName") @ConcordKey String projectName,
                                   @ApiParam @PathParam("repositoryName") @ConcordKey String repositoryName) {

        OrganizationEntry org = orgManager.assertAccess(orgName, true);
        ProjectEntry p = assertProject(org.getId(), projectName, ResourceAccessLevel.READER, true);
        RepositoryEntry r = assertRepository(p, repositoryName);

        return triggersDao.list(p.getId(), r.getId());
    }

    /**
     * Refresh process trigger definitions for all projects.
     *
     * @return
     */
    @POST
    @ApiOperation("Refresh trigger definitions for all projects")
    @javax.ws.rs.Path("/trigger/refresh")
    public Response refreshAll() {
        assertAdmin();
        repositoryDao.list().parallelStream().forEach(r -> {
            try {
                refresh(r);
            } catch (Exception e) {
                log.warn("refreshAll -> {} refresh failed: {}", r.getId(), e.getMessage());
            }
        });
        return Response.ok().build();
    }

    /**
     * Refresh process trigger definitions for the specified project and repository.
     *
     * @param projectName
     * @param repositoryName
     * @return
     */
    @POST
    @ApiOperation("Refresh trigger definitions for the specified project and repository")
    @javax.ws.rs.Path("/{orgName}/project/{projectName}/repo/{repositoryName}/trigger")
    public Response refresh(@ApiParam @PathParam("orgName") @ConcordKey String orgName,
                            @ApiParam @PathParam("projectName") @ConcordKey String projectName,
                            @ApiParam @PathParam("repositoryName") @ConcordKey String repositoryName) {

        OrganizationEntry org = orgManager.assertAccess(orgName, true);

        // allow READERs to refresh triggers - it helps with troubleshooting
        ProjectEntry p = assertProject(org.getId(), projectName, ResourceAccessLevel.READER, true);
        RepositoryEntry r = assertRepository(p, repositoryName);

        refresh(r);

        return Response.ok().build();
    }

    private void refresh(RepositoryEntry r) {
        ProjectDefinition pd;
        try {
            pd = repositoryManager.withLock(r.getUrl(), () -> {
                Repository repository = repositoryManager.fetch(r.getProjectId(), r);
                return projectLoader.loadProject(repository.path());
            });

            ProjectValidator.validate(pd);
        } catch (Exception e) {
            log.error("refresh ['{}'] -> project load error", r.getId(), e);
            throw new ConcordApplicationException("Refresh failed (repository ID: " + r.getId() + "): " + e.getMessage(), e);
        }

        tx(tx -> {
            triggersDao.delete(tx, r.getProjectId(), r.getId());

            pd.getTriggers().forEach(t -> {
                UUID triggerId = triggersDao.insert(tx,
                        r.getProjectId(), r.getId(), t.getName(),
                        t.getActiveProfiles(), t.getArguments(), t.getParams(), t.getCfg());

                TriggerProcessor processor = triggerProcessors.get(t.getName());
                if (processor != null) {
                    processor.process(tx, triggerId, t);
                }
            });
        });

        log.info("refresh ['{}'] -> done, triggers count: {}", r.getId(), pd.getTriggers().size());
    }

    private ProjectEntry assertProject(UUID orgId, String projectName, ResourceAccessLevel accessLevel, boolean orgMembersOnly) {
        if (projectName == null) {
            throw new ValidationErrorsException("Invalid project name");
        }

        return projectAccessManager.assertProjectAccess(orgId, null, projectName, accessLevel, orgMembersOnly);
    }

    private static void assertAdmin() {
        UserPrincipal p = UserPrincipal.assertCurrent();
        if (!p.isAdmin()) {
            throw new UnauthorizedException("Not authorized");
        }
    }
}
