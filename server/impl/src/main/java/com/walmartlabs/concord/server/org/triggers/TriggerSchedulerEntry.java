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

import com.walmartlabs.concord.sdk.Constants;

import java.io.Serializable;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

public class TriggerSchedulerEntry implements Serializable {

    private final Date fireAt;
    private final UUID triggerId;
    private final UUID orgId;
    private final UUID projectId;
    private final UUID repoId;
    private final String cronSpec;
    private final String timezone;
    private final Map<String, Object> arguments;
    private final Map<String, Object> cfg;

    public TriggerSchedulerEntry(Date fireAt, UUID triggerId, UUID orgId, UUID projectId, UUID repoId,
                                 String cronSpec, String timezone, Map<String, Object> arguments, Map<String, Object> cfg) {
        this.fireAt = fireAt;
        this.triggerId = triggerId;
        this.orgId = orgId;
        this.projectId = projectId;
        this.repoId = repoId;
        this.cronSpec = cronSpec;
        this.timezone = timezone;
        this.arguments = arguments;
        this.cfg = cfg;
    }

    public Date getFireAt() {
        return fireAt;
    }

    public UUID getTriggerId() {
        return triggerId;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public UUID getRepoId() {
        return repoId;
    }

    public Map<String, Object> getCfg() {
        return cfg;
    }

    public String getCronSpec() {
        return cronSpec;
    }

    public String getTimezone() {
        return timezone;
    }

    public Map<String, Object> getArguments() {
        return arguments;
    }

    public String getEntryPoint() {
        if (cfg == null) {
            return null;
        }

        return (String) cfg.get(Constants.Request.ENTRY_POINT_KEY);
    }

    @Override
    public String toString() {
        return "TriggerSchedulerEntry{" +
                "fireAt=" + fireAt +
                ", triggerId=" + triggerId +
                ", orgId=" + orgId +
                ", projectId=" + projectId +
                ", repoId=" + repoId +
                ", cronSpec='" + cronSpec + '\'' +
                ", timezone='" + timezone + '\'' +
                ", arguments=" + arguments +
                ", cfg=" + cfg +
                '}';
    }
}
