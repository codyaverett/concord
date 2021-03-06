package com.walmartlabs.concord.project.yaml.converter;

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
import com.walmartlabs.concord.project.InternalConstants;
import com.walmartlabs.concord.project.yaml.YamlConverterException;
import com.walmartlabs.concord.project.yaml.model.YamlFormCall;
import io.takari.bpm.model.UserTask;
import io.takari.bpm.model.form.FormExtension;

import java.util.*;
import java.util.stream.Collectors;


public class YamlFormCallConverter implements StepConverter<YamlFormCall> {

    private static final List<String> SUPPORTED_FORM_OPTIONS = Arrays.asList("yield", "runAs", "values", "fields");

    @Override
    @SuppressWarnings("unchecked")
    public Chunk convert(ConverterContext ctx, YamlFormCall s) throws YamlConverterException {
        Chunk c = new Chunk();
        String id = ctx.nextId();

        Map<String, Object> opts = (Map<String, Object>) StepConverter.deepConvert(s.getOptions());
        if (opts != null && opts.isEmpty()) {
            opts = null;
        }

        validate(opts, s.getLocation());

        c.addElement(new UserTask(id, new FormExtension(s.getKey(), opts)));
        c.addOutput(id);
        c.addSourceMap(id, toSourceMap(s, "Form: " + s.getKey()));

        return c;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getGroupsMap(Object ldapObj) {
        List<String> groupList = null;
        if (ldapObj instanceof List) {
            groupList = ((List<Map>) ldapObj).stream()
                    .map(group -> (String) group.get(InternalConstants.Forms.RUN_AS_GROUP_KEY))
                    .collect(Collectors.toList());
        } else if (ldapObj instanceof Map) {
            groupList = new ArrayList<>();
            groupList.add((String) ((Map) ldapObj).get(InternalConstants.Forms.RUN_AS_GROUP_KEY));
        }

        return Collections.singletonMap(InternalConstants.Forms.RUN_AS_GROUP_KEY, groupList);
    }

    private static void validate(Map<String, Object> opts, JsonLocation loc) {
        if (opts == null) {
            return;
        }

        Set<String> keys = new HashSet<>(opts.keySet());
        keys.removeAll(SUPPORTED_FORM_OPTIONS);

        if (keys.isEmpty()) {
            return;
        }

        throw new IllegalArgumentException("'" + keys + "' are not form supported options. Supported options are only:"
                + SUPPORTED_FORM_OPTIONS + ". Error in form step @:" + loc);
    }
}
