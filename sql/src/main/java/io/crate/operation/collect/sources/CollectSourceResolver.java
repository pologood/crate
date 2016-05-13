/*
 * Licensed to CRATE Technology GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package io.crate.operation.collect.sources;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import io.crate.analyze.EvaluatingNormalizer;
import io.crate.executor.transport.TransportActionProvider;
import io.crate.metadata.Functions;
import io.crate.metadata.NestedReferenceResolver;
import io.crate.metadata.PartitionName;
import io.crate.metadata.RowGranularity;
import io.crate.metadata.information.InformationSchemaInfo;
import io.crate.metadata.sys.SysClusterTableInfo;
import io.crate.metadata.sys.SysSchemaInfo;
import io.crate.metadata.table.TableInfo;
import io.crate.operation.ImplementationSymbolVisitor;
import io.crate.operation.collect.CrateCollector;
import io.crate.operation.collect.JobCollectContext;
import io.crate.operation.projectors.ProjectionToProjectorVisitor;
import io.crate.operation.projectors.ProjectorFactory;
import io.crate.operation.projectors.RowReceiver;
import io.crate.planner.node.ExecutionPhaseVisitor;
import io.crate.planner.node.dql.CollectPhase;
import io.crate.planner.node.dql.FileUriCollectPhase;
import io.crate.planner.node.dql.RoutedCollectPhase;
import io.crate.planner.node.dql.TableFunctionCollectPhase;
import org.elasticsearch.action.bulk.BulkRetryCoordinatorPool;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.Singleton;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.threadpool.ThreadPool;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Singleton
public class CollectSourceResolver {

    private static final VoidCollectSource VOID_COLLECT_SERVICE = new VoidCollectSource();

    private final Map<String, CollectSource> nodeDocCollectSources = new HashMap<>();
    private final ShardCollectSource shardCollectSource;
    private final CollectSource fileCollectSource;
    private final CollectSource singleRowSource;
    private final ClusterService clusterService;
    private final CollectPhaseVisitor visitor;
    private final ProjectorSetupCollectSource tableFunctionSource;

    @Inject
    public CollectSourceResolver(ClusterService clusterService,
                                 IndexNameExpressionResolver indexNameExpressionResolver,
                                 Functions functions,
                                 NestedReferenceResolver clusterReferenceResolver,
                                 Settings settings,
                                 ThreadPool threadPool,
                                 TransportActionProvider transportActionProvider,
                                 BulkRetryCoordinatorPool bulkRetryCoordinatorPool,
                                 InformationSchemaInfo informationSchemaInfo,
                                 SysSchemaInfo sysSchemaInfo,
                                 ShardCollectSource shardCollectSource,
                                 FileCollectSource fileCollectSource,
                                 TableFunctionCollectSource tableFunctionCollectSource,
                                 SingleRowSource singleRowSource,
                                 SystemCollectSource systemCollectSource) {
        this.clusterService = clusterService;

        ImplementationSymbolVisitor nodeImplementationSymbolVisitor = new ImplementationSymbolVisitor(functions);
        EvaluatingNormalizer normalizer = new EvaluatingNormalizer(functions, RowGranularity.NODE, clusterReferenceResolver);
        ProjectorFactory projectorFactory = new ProjectionToProjectorVisitor(
                clusterService,
                functions,
                indexNameExpressionResolver,
                threadPool,
                settings,
                transportActionProvider,
                bulkRetryCoordinatorPool,
                nodeImplementationSymbolVisitor,
                normalizer
        );
        this.shardCollectSource = shardCollectSource;
        this.fileCollectSource = new ProjectorSetupCollectSource(fileCollectSource, projectorFactory);
        this.singleRowSource = new ProjectorSetupCollectSource(singleRowSource, projectorFactory);
        this.tableFunctionSource = new ProjectorSetupCollectSource(tableFunctionCollectSource, projectorFactory);

        nodeDocCollectSources.put(SysClusterTableInfo.IDENT.fqn(), this.singleRowSource);

        ProjectorSetupCollectSource sysSource = new ProjectorSetupCollectSource(systemCollectSource, projectorFactory);
        for (TableInfo tableInfo : sysSchemaInfo) {
            if (tableInfo.rowGranularity().equals(RowGranularity.DOC)) {
                nodeDocCollectSources.put(tableInfo.ident().fqn(), sysSource);
            }
        }
        for (TableInfo tableInfo : informationSchemaInfo) {
            nodeDocCollectSources.put(tableInfo.ident().fqn(), sysSource);
        }

        visitor = new CollectPhaseVisitor();
    }

    private class CollectPhaseVisitor extends ExecutionPhaseVisitor<Void, CollectSource> {
        @Override
        public CollectSource visitFileUriCollectPhase(FileUriCollectPhase phase, Void context) {
            return fileCollectSource;
        }

        @Override
        public CollectSource visitTableFunctionCollect(TableFunctionCollectPhase phase, Void context) {
            return tableFunctionSource;
        }

        @Override
        public CollectSource visitRoutedCollectPhase(RoutedCollectPhase phase, Void context) {
            assert phase.isRouted() : "collectPhase must contain a routing";
            String localNodeId = clusterService.state().nodes().localNodeId();
            Set<String> routingNodes = phase.routing().nodes();
            if (!routingNodes.contains(localNodeId)) {
                throw new IllegalStateException("unsupported routing");
            }

            Map<String, Map<String, List<Integer>>> locations = phase.routing().locations();
            if (phase.routing().containsShards(localNodeId)) {
                return shardCollectSource;
            }

            Map<String, List<Integer>> indexShards = locations.get(localNodeId);
            if (indexShards == null) {
                throw new IllegalStateException("Can't resolve CollectService for collectPhase: " + phase);
            }
            if (indexShards.size() == 0) {
                // select * from sys.nodes
                return singleRowSource;
            }

            String indexName = Iterables.getFirst(indexShards.keySet(), null);
            if (indexName == null) {
                throw new IllegalStateException("Can't resolve CollectService for collectPhase: " + phase);
            }
            if (phase.maxRowGranularity() == RowGranularity.DOC && PartitionName.isPartition(indexName)) {
                // partitioned table without any shards; nothing to collect
                return VOID_COLLECT_SERVICE;
            }
            assert indexShards.size() == 1 : "routing without shards that operates on non user-tables may only contain 1 index/table";
            CollectSource collectSource = nodeDocCollectSources.get(indexName);
            if (collectSource == null) {
                throw new IllegalStateException("Can't resolve CollectService for collectPhase: " + phase);
            }
            return collectSource;
        }
    }

    public CollectSource getService(CollectPhase collectPhase) {
        return visitor.process(collectPhase, null);
    }

    private static class VoidCollectSource implements CollectSource {

        @Override
        public CollectSourceContext getCollectors(CollectPhase collectPhase, RowReceiver downstream, JobCollectContext jobCollectContext) {
            return new CollectSourceContext(ImmutableList.<CrateCollector>of(), ImmutableList.<RowReceiver>of());
        }
    }
}
