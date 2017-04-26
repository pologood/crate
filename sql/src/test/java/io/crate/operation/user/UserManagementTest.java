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

package io.crate.operation.user;

import io.crate.action.sql.DDLStatementDispatcher;
import io.crate.analyze.CreateUserAnalyzedStatement;
import io.crate.test.integration.CrateUnitTest;
import org.junit.Test;

import java.util.concurrent.ExecutionException;

import static org.hamcrest.Matchers.containsString;

public class UserManagementTest extends CrateUnitTest {

    DDLStatementDispatcher ddlDispatcherCommunityEdition = new DDLStatementDispatcher(
            null, null, null, null, null, null,
            new UserManagerProvider(null, null),
            null, null, null
        );

    @Test
    public void testNoopUserManagerLoaded() throws Exception {
        expectedException.expect(ExecutionException.class);
        expectedException.expectMessage(containsString("User management is only supported in enterprise version"));
        ddlDispatcherCommunityEdition.dispatch(new CreateUserAnalyzedStatement("root"), null).get();
    }

}