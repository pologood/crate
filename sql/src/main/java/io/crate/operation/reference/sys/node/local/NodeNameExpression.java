/*
 * Licensed to Crate under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.  Crate licenses this file
 * to you under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial
 * agreement.
 */

package io.crate.operation.reference.sys.node.local;

import io.crate.metadata.ReferenceImplementation;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.discovery.Discovery;

class NodeNameExpression implements ReferenceImplementation<BytesRef> {

    private final Discovery discovery;
    private BytesRef value = null;

    NodeNameExpression(Discovery discovery) {
        this.discovery = discovery;
    }

    @Override
    public BytesRef value() {
        // value could not be ready on node start-up, but is static once set
        DiscoveryNode node = discovery.localNode();
        if (value == null && node != null) {
            value = new BytesRef(node.getName());
        }
        return value;
    }
}
