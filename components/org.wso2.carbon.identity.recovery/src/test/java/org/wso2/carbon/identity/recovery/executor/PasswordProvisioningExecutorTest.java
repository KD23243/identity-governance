/*
 * Copyright (c) 2025, WSO2 LLC. (https://www.wso2.com) All Rights Reserved.
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.identity.recovery.executor;

import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import org.wso2.carbon.identity.common.testng.WithCarbonHome;
import org.wso2.carbon.identity.flow.execution.engine.Constants;
import org.wso2.carbon.identity.flow.execution.engine.model.ExecutorResponse;
import org.wso2.carbon.identity.flow.execution.engine.model.FlowExecutionContext;
import org.wso2.carbon.identity.flow.execution.engine.model.FlowUser;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/**
 * Unit tests for {@link PasswordProvisioningExecutor}.
 * <p>
 * The credential provisioning logic has been consolidated into {@link UserProvisioningExecutor}; this executor now
 * only captures the password field onto the flow user.
 */
@WithCarbonHome
public class PasswordProvisioningExecutorTest {

    private static final String PASSWORD_KEY = "password";
    private static final String USERNAME = "test@wso2.com";

    private PasswordProvisioningExecutor executor;

    @BeforeMethod
    public void setUp() {

        executor = new PasswordProvisioningExecutor();
    }

    @Test
    public void testGetName() {

        assertEquals(executor.getName(), "PasswordProvisioningExecutor");
    }

    @Test
    public void testGetAMRValue() {

        assertEquals(executor.getAMRValue(), "BasicAuthenticator");
    }

    @Test
    public void testGetInitiationData() {

        assertTrue(executor.getInitiationData().contains(PASSWORD_KEY));
    }

    @Test
    public void testRollbackReturnsNull() {

        assertEquals(executor.rollback(mock(FlowExecutionContext.class)), null);
    }

    @Test
    public void testExecuteWithMissingPasswordAndCredentials() {

        FlowExecutionContext context = mock(FlowExecutionContext.class);
        when(context.getUserInputData()).thenReturn(Collections.emptyMap());
        when(context.getFlowUser()).thenReturn(new FlowUser());

        ExecutorResponse response = executor.execute(context);

        assertEquals(response.getResult(), Constants.ExecutorStatus.STATUS_USER_INPUT_REQUIRED);
        assertTrue(response.getRequiredData().contains(PASSWORD_KEY));
    }

    @Test
    public void testExecuteCapturesPasswordAndCompletes() {

        FlowExecutionContext context = mock(FlowExecutionContext.class);
        FlowUser flowUser = new FlowUser();
        flowUser.setUsername(USERNAME);

        Map<String, String> userInputData = new HashMap<>();
        userInputData.put(PASSWORD_KEY, "Password123");
        when(context.getUserInputData()).thenReturn(userInputData);
        when(context.getFlowUser()).thenReturn(flowUser);

        ExecutorResponse response = executor.execute(context);

        assertEquals(response.getResult(), Constants.ExecutorStatus.STATUS_COMPLETE);
        assertEquals(new String(flowUser.getUserCredentials().get(PASSWORD_KEY)), "Password123");
    }

    @Test
    public void testExecuteWithExistingCredentialsCompletes() {

        FlowExecutionContext context = mock(FlowExecutionContext.class);
        FlowUser flowUser = new FlowUser();
        Map<String, char[]> credentials = new HashMap<>();
        credentials.put(PASSWORD_KEY, "Existing123".toCharArray());
        flowUser.setUserCredentials(credentials);

        when(context.getUserInputData()).thenReturn(Collections.emptyMap());
        when(context.getFlowUser()).thenReturn(flowUser);

        ExecutorResponse response = executor.execute(context);

        assertEquals(response.getResult(), Constants.ExecutorStatus.STATUS_COMPLETE);
    }
}
