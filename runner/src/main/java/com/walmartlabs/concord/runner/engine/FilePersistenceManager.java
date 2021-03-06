package com.walmartlabs.concord.runner.engine;

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

import com.walmartlabs.concord.common.IOUtils;
import io.takari.bpm.api.ExecutionException;
import io.takari.bpm.persistence.PersistenceManager;
import io.takari.bpm.state.ProcessInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

public class FilePersistenceManager implements PersistenceManager {

    private static final Logger log = LoggerFactory.getLogger(FilePersistenceManager.class);

    private final Path dir;

    public FilePersistenceManager(Path dir) {
        this.dir = dir;
    }

    @Override
    public void save(ProcessInstance state) throws ExecutionException {
        Path p = dir.resolve(state.getId().toString());

        try {
            Path tmp = IOUtils.createTempFile(state.getId().toString(), "state");
            try (ObjectOutputStream out = new ObjectOutputStream(Files.newOutputStream(tmp))) {
                out.writeObject(state);
            }
            Files.move(tmp, p, REPLACE_EXISTING);
        } catch (IOException e) {
            throw new ExecutionException("Error while saving state", e);
        }

        log.debug("save ['{}', '{}'] -> done, {}", state.getBusinessKey(), state.getId(), p);
    }

    @Override
    public ProcessInstance get(UUID id) {
        Path p = dir.resolve(id.toString());
        if (!Files.exists(p)) {
            return null;
        }

        try (ObjectInputStream in = new ThreadLocalClassLoaderObjectInputStream(Files.newInputStream(p))) {
            return (ProcessInstance) in.readObject();
        } catch (ClassNotFoundException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void remove(UUID id) {
        Path p = dir.resolve(id.toString());
        if (!Files.exists(p)) {
            return;
        }

        try {
            Files.delete(p);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        log.debug("remove ['{}'] -> done, {}", id, p);
    }

    private static class ThreadLocalClassLoaderObjectInputStream extends ObjectInputStream {

        private final ClassLoader classLoader;

        public ThreadLocalClassLoaderObjectInputStream(InputStream inputStream) throws IOException {
            super(inputStream);
            this.classLoader = Thread.currentThread().getContextClassLoader();
        }

        @Override
        protected Class<?> resolveClass(final ObjectStreamClass objectStreamClass)
                throws IOException, ClassNotFoundException {

            try {
                return Class.forName(objectStreamClass.getName(), false, classLoader);
            } catch (final ClassNotFoundException e) {
                return super.resolveClass(objectStreamClass);
            }
        }
    }
}
