package com.walmartlabs.concord.project.yaml.model;

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

import com.fasterxml.jackson.core.JsonLocation;
import io.takari.parc.Seq;

import java.util.Map;

public class YamlGroup extends YamlStep {

    private final Seq<YamlStep> steps;
    private final Map<String, Object> options;

    public YamlGroup(JsonLocation location, Seq<YamlStep> steps, Map<String, Object> options) {
        super(location);
        this.steps = steps;
        this.options = options;
    }

    public Seq<YamlStep> getSteps() {
        return steps;
    }

    public Map<String, Object> getOptions() {
        return options;
    }

    @Override
    public String toString() {
        return "YamlGroup{" +
                "steps=" + steps +
                ", options=" + options +
                '}';
    }
}
