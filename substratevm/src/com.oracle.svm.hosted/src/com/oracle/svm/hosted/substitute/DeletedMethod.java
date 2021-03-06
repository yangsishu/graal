/*
 * Copyright (c) 2017, 2017, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.hosted.substitute;

import java.lang.reflect.Method;

import org.graalvm.compiler.debug.DebugContext;
import org.graalvm.compiler.java.FrameStateBuilder;
import org.graalvm.compiler.nodes.CallTargetNode.InvokeKind;
import org.graalvm.compiler.nodes.ConstantNode;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.nodes.UnwindNode;
import org.graalvm.compiler.nodes.ValueNode;

import com.oracle.graal.pointsto.meta.HostedProviders;
import com.oracle.svm.core.annotate.Delete;
import com.oracle.svm.core.meta.SubstrateObjectConstant;
import com.oracle.svm.core.util.VMError;
import com.oracle.svm.hosted.annotation.CustomSubstitutionMethod;
import com.oracle.svm.hosted.option.HostedOptionParser;
import com.oracle.svm.hosted.phases.HostedGraphKit;

import jdk.vm.ci.meta.ResolvedJavaMethod;

public class DeletedMethod extends CustomSubstitutionMethod {

    public static final String NATIVE_MESSAGE = String.format(
                    "Native method. If you intend to use the Java Native Interface (JNI), specify %1$s+JNI and see also %1$sJNIConfigurationFiles=<path> (use %1$s+PrintFlags for details)",
                    HostedOptionParser.HOSTED_OPTION_PREFIX);

    private final Delete deleteAnnotation;

    public DeletedMethod(ResolvedJavaMethod original, Delete deleteAnnotation) {
        super(original);
        this.deleteAnnotation = deleteAnnotation;
    }

    static final Method reportErrorMethod;

    static {
        try {
            reportErrorMethod = VMError.class.getDeclaredMethod("unsupportedFeature", String.class);
        } catch (NoSuchMethodException ex) {
            throw VMError.shouldNotReachHere(ex);
        }
    }

    @Override
    public StructuredGraph buildGraph(DebugContext debug, ResolvedJavaMethod method, HostedProviders providers, Purpose purpose) {
        return buildGraph(debug, method, providers, deleteAnnotation.value());
    }

    public static StructuredGraph buildGraph(DebugContext debug, ResolvedJavaMethod method, HostedProviders providers, String message) {
        HostedGraphKit kit = new HostedGraphKit(debug, providers, method);
        StructuredGraph graph = kit.getGraph();
        FrameStateBuilder state = new FrameStateBuilder(null, method, graph);
        state.initializeForMethodStart(null, true, providers.getGraphBuilderPlugins());

        /*
         * A random, but unique and consistent, number for every invoke. This is necessary because
         * we, e.g., look up static analysis results by bci.
         */
        int bci = 0;
        graph.start().setStateAfter(state.create(bci++, graph.start()));

        String msg = AnnotationSubstitutionProcessor.deleteErrorMessage(method, message, false);
        ValueNode msgNode = ConstantNode.forConstant(SubstrateObjectConstant.forObject(msg), providers.getMetaAccess(), graph);
        ValueNode exceptionNode = kit.createInvokeWithExceptionAndUnwind(providers.getMetaAccess().lookupJavaMethod(reportErrorMethod), InvokeKind.Static, state, bci++, bci++, msgNode);

        kit.append(new UnwindNode(exceptionNode));
        kit.mergeUnwinds();

        assert graph.verify();
        return graph;
    }
}
