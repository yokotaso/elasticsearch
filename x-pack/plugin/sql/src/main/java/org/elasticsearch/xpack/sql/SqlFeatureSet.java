/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.sql;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.license.XPackLicenseState;
import org.elasticsearch.protocol.xpack.XPackUsageRequest;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.xpack.core.XPackFeatureSet;
import org.elasticsearch.xpack.core.XPackField;
import org.elasticsearch.xpack.core.XPackSettings;
import org.elasticsearch.xpack.core.action.XPackUsageFeatureAction;
import org.elasticsearch.xpack.core.action.XPackUsageFeatureResponse;
import org.elasticsearch.xpack.core.action.XPackUsageFeatureTransportAction;
import org.elasticsearch.xpack.core.sql.SqlFeatureSetUsage;
import org.elasticsearch.xpack.core.watcher.common.stats.Counters;
import org.elasticsearch.xpack.sql.plugin.SqlStatsAction;
import org.elasticsearch.xpack.sql.plugin.SqlStatsRequest;
import org.elasticsearch.xpack.sql.plugin.SqlStatsResponse;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class SqlFeatureSet implements XPackFeatureSet {

    private final boolean enabled;
    private final XPackLicenseState licenseState;
    private final Client client;
    
    @Inject
    public SqlFeatureSet(Settings settings, @Nullable XPackLicenseState licenseState, Client client) {
        this.enabled = XPackSettings.SQL_ENABLED.get(settings);
        this.licenseState = licenseState;
        this.client = client;
    }
    
    @Override
    public String name() {
        return XPackField.SQL;
    }

    @Override
    public boolean available() {
        return licenseState != null && licenseState.isSqlAllowed();
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    public static class UsageTransportAction extends XPackUsageFeatureTransportAction {
        private final boolean enabled;
        private final XPackLicenseState licenseState;
        private final Client client;

        @Inject
        public UsageTransportAction(TransportService transportService, ClusterService clusterService, ThreadPool threadPool,
                                    ActionFilters actionFilters, IndexNameExpressionResolver indexNameExpressionResolver,
                                    Settings settings, XPackLicenseState licenseState, Client client) {
            super(XPackUsageFeatureAction.SQL.name(), transportService, clusterService, threadPool, actionFilters,
                indexNameExpressionResolver);
            this.enabled = XPackSettings.SQL_ENABLED.get(settings);
            this.licenseState = licenseState;
            this.client = client;
        }

        @Override
        protected void masterOperation(XPackUsageRequest request, ClusterState state, ActionListener<XPackUsageFeatureResponse> listener) {
            boolean available = licenseState.isSqlAllowed();
            if (enabled) {
                SqlStatsRequest sqlRequest = new SqlStatsRequest();
                sqlRequest.includeStats(true);
                client.execute(SqlStatsAction.INSTANCE, sqlRequest, ActionListener.wrap(r -> {
                    List<Counters> countersPerNode = r.getNodes()
                        .stream()
                        .map(SqlStatsResponse.NodeStatsResponse::getStats)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
                    Counters mergedCounters = Counters.merge(countersPerNode);
                    SqlFeatureSetUsage usage = new SqlFeatureSetUsage(available, enabled, mergedCounters.toNestedMap());
                    listener.onResponse(new XPackUsageFeatureResponse(usage));
                }, listener::onFailure));
            } else {
                SqlFeatureSetUsage usage = new SqlFeatureSetUsage(available, enabled, Collections.emptyMap());
                listener.onResponse(new XPackUsageFeatureResponse(usage));
            }
        }
    }
}
