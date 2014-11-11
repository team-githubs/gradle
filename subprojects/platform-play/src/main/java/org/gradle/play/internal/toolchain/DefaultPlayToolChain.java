/*
 * Copyright 2014 the original author or authors.
 *
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
 */

package org.gradle.play.internal.toolchain;

import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.internal.file.FileResolver;
import org.gradle.api.internal.tasks.compile.daemon.CompilerDaemonManager;
import org.gradle.api.tasks.WorkResult;
import org.gradle.api.tasks.compile.BaseForkOptions;
import org.gradle.language.base.internal.compile.CompileSpec;
import org.gradle.language.base.internal.compile.Compiler;
import org.gradle.platform.base.internal.toolchain.ToolProvider;
import org.gradle.play.internal.twirl.DaemonTwirlCompiler;
import org.gradle.play.internal.twirl.TwirlCompiler;
import org.gradle.play.internal.twirl.TwirlCompileSpec;
import org.gradle.play.internal.twirl.TwirlCompileSpecFactory;
import org.gradle.play.internal.twirl.VersionedTwirlCompileSpec;
import org.gradle.play.platform.PlayPlatform;
import org.gradle.util.TreeVisitor;
import org.gradle.util.WrapUtil;

import java.util.Map;

public class DefaultPlayToolChain implements PlayToolChainInternal {
    private FileResolver fileResolver;
    private CompilerDaemonManager compilerDaemonManager;
    private final ConfigurationContainer configurationContainer;
    private final DependencyHandler dependencyHandler;

    public DefaultPlayToolChain(FileResolver fileResolver, CompilerDaemonManager compilerDaemonManager, ConfigurationContainer configurationContainer, DependencyHandler dependencyHandler) {
        this.fileResolver = fileResolver;
        this.compilerDaemonManager = compilerDaemonManager;
        this.configurationContainer = configurationContainer;
        this.dependencyHandler = dependencyHandler;
    }

    public String getName() {
        return String.format("PlayToolchain");
    }

    public String getDisplayName() {
        return String.format("Default Play Toolchain");
    }

    public ToolProvider select(PlayPlatform targetPlatform) {
        return new PlayToolProvider(targetPlatform);
    }

    private class PlayToolProvider implements ToolProvider {
        private PlayPlatform targetPlatform;

        public PlayToolProvider(PlayPlatform targetPlatform) {
            this.targetPlatform = targetPlatform;
        }

        public <T extends CompileSpec> Compiler<T> newCompiler(T spec) {
            if (spec instanceof TwirlCompileSpec) {
                TwirlCompileSpec twirlCompileSpec = (TwirlCompileSpec)spec;
                VersionedTwirlCompileSpec versionedSpec = TwirlCompileSpecFactory.create(twirlCompileSpec, targetPlatform);
                Dependency compilerDependency = dependencyHandler.create(versionedSpec.getDependencyNotation());

                Configuration templateCompilerClasspath = configurationContainer.detachedConfiguration(compilerDependency);
                DaemonTwirlCompiler compiler = new DaemonTwirlCompiler(fileResolver.resolve("."), templateCompilerClasspath.getFiles(), new TwirlCompiler(), compilerDaemonManager, new BaseForkOptions());
                @SuppressWarnings("unchecked") Compiler<T> twirlCompileSpecCompiler = (Compiler<T>) new MappingSpecCompiler<TwirlCompileSpec, VersionedTwirlCompileSpec>(compiler, WrapUtil.toMap(twirlCompileSpec, versionedSpec));
                return twirlCompileSpecCompiler;
            }
            return null;
        }

        public boolean isAvailable() {
            return true;
        }

        public void explain(TreeVisitor<? super String> visitor) {

        }

        private class MappingSpecCompiler<T extends CompileSpec, V extends CompileSpec> implements Compiler<T>  {
            private Compiler<V> delegate;
            private final Map<T, V> mapping;

            public MappingSpecCompiler(Compiler<V> delegate, Map<T, V> mapping){
                this.delegate = delegate;
                this.mapping = mapping;
            }

            public WorkResult execute(T spec) {
                return delegate.execute(mapping.get(spec));
            }
        }
    }
}
