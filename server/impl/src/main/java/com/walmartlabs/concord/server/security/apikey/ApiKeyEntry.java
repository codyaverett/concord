package com.walmartlabs.concord.server.security.apikey;

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


import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.walmartlabs.concord.common.validation.ConcordKey;

import java.io.Serializable;
import java.util.Date;
import java.util.UUID;

@JsonInclude(Include.NON_NULL)
public class ApiKeyEntry implements Serializable {

    private final UUID id;

    @ConcordKey
    private final String name;

    private final Date expiredAt;

    @JsonCreator
    public ApiKeyEntry(@JsonProperty("id") UUID id,
                       @JsonProperty("name") String name,
                       @JsonProperty("expiredAt") Date expiredAt) {

        this.id = id;
        this.name = name;
        this.expiredAt = expiredAt;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Date getExpiredAt() {
        return expiredAt;
    }

    @Override
    public String toString() {
        return "ApiKeyEntry{" +
                "id=" + id +
                ", name='" + name + '\'' +
                ", expiredAt=" + expiredAt +
                '}';
    }
}
