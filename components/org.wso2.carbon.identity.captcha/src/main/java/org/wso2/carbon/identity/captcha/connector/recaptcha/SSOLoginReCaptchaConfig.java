/*
 * Copyright (c) 2016, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.identity.captcha.connector.recaptcha;

import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.identity.application.authentication.framework.AuthenticationFlowHandler;
import org.wso2.carbon.identity.application.authentication.framework.context.AuthenticationContext;
import org.wso2.carbon.identity.application.authentication.framework.model.AuthenticatedIdPData;
import org.wso2.carbon.identity.application.authentication.framework.util.FrameworkUtils;
import org.wso2.carbon.identity.application.common.model.Property;
import org.wso2.carbon.identity.captcha.connector.CaptchaPostValidationResponse;
import org.wso2.carbon.identity.captcha.connector.CaptchaPreValidationResponse;
import org.wso2.carbon.identity.captcha.exception.CaptchaException;
import org.wso2.carbon.identity.captcha.internal.CaptchaDataHolder;
import org.wso2.carbon.identity.captcha.util.CaptchaConstants;
import org.wso2.carbon.identity.captcha.util.CaptchaUtil;
import org.wso2.carbon.identity.core.util.IdentityTenantUtil;
import org.wso2.carbon.identity.core.util.IdentityUtil;
import org.wso2.carbon.identity.governance.IdentityGovernanceException;
import org.wso2.carbon.identity.governance.IdentityGovernanceService;
import org.wso2.carbon.identity.governance.common.IdentityConnectorConfig;
import org.wso2.carbon.identity.multi.attribute.login.mgt.ResolvedUserResult;
import org.wso2.carbon.user.core.util.UserCoreUtil;
import org.wso2.carbon.utils.multitenancy.MultitenantUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;

import static org.wso2.carbon.identity.captcha.util.CaptchaConstants.AUTH_FAILURE;
import static org.wso2.carbon.identity.captcha.util.CaptchaConstants.ConnectorConfig.SSO_LOGIN_RECAPTCHA_ENABLED;
import static org.wso2.carbon.identity.captcha.util.CaptchaConstants.ConnectorConfig.SSO_LOGIN_RECAPTCHA_ENABLE_ALWAYS;
import static org.wso2.carbon.identity.captcha.util.CaptchaConstants.ConnectorConfig.SSO_LOGIN_RECAPTCHA_MAX_ATTEMPTS;
import static org.wso2.carbon.identity.captcha.util.CaptchaConstants.ReCaptchaConnectorPropertySuffixes;
import static org.wso2.carbon.identity.captcha.util.CaptchaConstants.SSO_LOGIN_RECAPTCHA_CONNECTOR_NAME;

/**
 * reCaptcha login identity governance connector.
 */
public class SSOLoginReCaptchaConfig extends AbstractReCaptchaConnector implements IdentityConnectorConfig {

    private static final Log log = LogFactory.getLog(SSOLoginReCaptchaConfig.class);

    private static final String SECURED_DESTINATIONS = "/commonauth,/samlsso,/oauth2";
    private static final String USERNAME = "username";
    private static final String PASSWORD = "password";
    private static final String OTPCODE = "OTPCode";
    private static final String EMAIL_OTP_AUTHENTICATOR_NAME = "email-otp-authenticator";
    private static final String SMS_OTP_AUTHENTICATOR_NAME = "sms-otp-authenticator";
    private static final String FAILED_LOGIN_ATTEMPTS_CLAIM_URI = "http://wso2.org/claims/identity/failedLoginAttempts";
    private static final String FAILED_EMAIL_OTP_ATTEMPTS_CLAIM_URI
            = "http://wso2.org/claims/identity/failedEmailOtpAttempts";
    private static final String FAILED_SMS_OTP_ATTEMPTS_CLAIM_URI
            = "http://wso2.org/claims/identity/failedSmsOtpAttempts";

    private IdentityGovernanceService identityGovernanceService;

    @Override
    public void init(IdentityGovernanceService identityGovernanceService) {

        this.identityGovernanceService = identityGovernanceService;
    }

    @Override
    public int getPriority() {
        return 20;
    }

    @Override
    public boolean canHandle(ServletRequest servletRequest, ServletResponse servletResponse) throws CaptchaException {

        if (!CaptchaDataHolder.getInstance().isForcefullyEnabledRecaptchaForAllTenants() &&
                !isReCaptchaEnabledForSSOLogin(servletRequest)) {
            return false;
        }

        String currentPath = ((HttpServletRequest) servletRequest).getRequestURI();
        if (StringUtils.isBlank(currentPath) || !CaptchaUtil.isPathAvailable(currentPath, SECURED_DESTINATIONS)) {
            return false;
        }

        if (!containsBasicAuthenticatorAttributes(servletRequest) &&
                !containsOTPAuthenticatorAttributes(servletRequest)) {
            return false;
        }

        return true;
    }

    @Override
    public CaptchaPreValidationResponse preValidate(ServletRequest servletRequest, ServletResponse servletResponse)
            throws CaptchaException {

        CaptchaPreValidationResponse preValidationResponse = new CaptchaPreValidationResponse();

        String username = servletRequest.getParameter("username");

        String sessionDataKey = servletRequest.getParameter(FrameworkUtils.SESSION_DATA_KEY);
        AuthenticationContext context = FrameworkUtils.getAuthenticationContextFromCache(sessionDataKey);
        String tenantDomain = getTenant(context, username);

        // Resolve the username from the multi attribute login service when the multi attribute login is enabled.
        ResolvedUserResult resolvedUserResult = FrameworkUtils.processMultiAttributeLoginIdentification(
                MultitenantUtils.getTenantAwareUsername(username), tenantDomain);
        if (resolvedUserResult != null && ResolvedUserResult.UserResolvedStatus.SUCCESS.
                equals(resolvedUserResult.getResolvedStatus())) {
            username = UserCoreUtil.addTenantDomainToEntry(resolvedUserResult.getUser().getUsername(),
                    tenantDomain);
        }

        // Verify whether recaptcha is enforced always for basic authentication.
        Property[] connectorConfigs = null;
        try {
            connectorConfigs = identityGovernanceService.getConfiguration(new String[]{
                    SSO_LOGIN_RECAPTCHA_CONNECTOR_NAME + ReCaptchaConnectorPropertySuffixes.ENABLE_ALWAYS},
                    tenantDomain);
        } catch (IdentityGovernanceException e) {
            // Can happen due to invalid user/ invalid tenant/ invalid configuration.
            log.error("Unable to load connector configuration.", e);
        }

        if (CaptchaDataHolder.getInstance().isForcefullyEnabledRecaptchaForAllTenants() || (connectorConfigs != null &&
                connectorConfigs.length != 0 && (Boolean.valueOf(connectorConfigs[0].getValue())))) {

            Map<String, String> params = new HashMap<>();
            params.put(AUTH_FAILURE, CaptchaConstants.TRUE);
            params.put(CaptchaConstants.AUTH_FAILURE_MSG, CaptchaConstants.RECAPTCHA_FAIL_MSG_KEY);
            preValidationResponse.setCaptchaAttributes(params);
            preValidationResponse.setOnCaptchaFailRedirectUrls(CaptchaUtil.getOnFailedLoginUrls());
            preValidationResponse.setCaptchaValidationRequired(true);
        } else {
            String failedLoginAttemptsClaimUri = FAILED_LOGIN_ATTEMPTS_CLAIM_URI;
            if (EMAIL_OTP_AUTHENTICATOR_NAME.equals(context.getCurrentAuthenticator())) {
                failedLoginAttemptsClaimUri = FAILED_EMAIL_OTP_ATTEMPTS_CLAIM_URI;
            }
            if (SMS_OTP_AUTHENTICATOR_NAME.equals(context.getCurrentAuthenticator())) {
                failedLoginAttemptsClaimUri = FAILED_SMS_OTP_ATTEMPTS_CLAIM_URI;
            }
            if (CaptchaUtil.isMaximumFailedLoginAttemptsReached(MultitenantUtils.getTenantAwareUsername(username),
                    tenantDomain, failedLoginAttemptsClaimUri)) {
                preValidationResponse.setCaptchaValidationRequired(true);
                preValidationResponse.setMaxFailedLimitReached(true);

                preValidationResponse.setOnCaptchaFailRedirectUrls(CaptchaUtil.getOnFailedLoginUrls());
                Map<String, String> params = new HashMap<>();
                params.put(CaptchaConstants.RE_CAPTCHA, CaptchaConstants.TRUE);
                params.put(CaptchaConstants.AUTH_FAILURE, CaptchaConstants.TRUE);
                params.put(CaptchaConstants.AUTH_FAILURE_MSG, CaptchaConstants.RECAPTCHA_FAIL_MSG_KEY);
                preValidationResponse.setCaptchaAttributes(params);
            }
        }
        // Post validate all requests
        preValidationResponse.setMaxFailedLimitReached(true);
        preValidationResponse.setPostValidationRequired(true);

        return preValidationResponse;
    }

    @Override
    public CaptchaPostValidationResponse postValidate(ServletRequest servletRequest, ServletResponse servletResponse)
            throws CaptchaException {

        if (!StringUtils.isBlank(CaptchaConstants.getEnableSecurityMechanism())) {
            CaptchaConstants.removeEnabledSecurityMechanism();
            CaptchaPostValidationResponse validationResponse = new CaptchaPostValidationResponse();
            validationResponse.setSuccessfulAttempt(false);
            validationResponse.setEnableCaptchaResponsePath(true);
            Map<String, String> params = new HashMap<>();
            params.put(CaptchaConstants.RE_CAPTCHA, CaptchaConstants.TRUE);
            validationResponse.setCaptchaAttributes(params);
            return validationResponse;
        }
        return null;
    }

    @Override
    public String getName() {

        return SSO_LOGIN_RECAPTCHA_CONNECTOR_NAME;
    }

    @Override
    public String getFriendlyName() {

        return "reCaptcha for SSO Login";
    }

    @Override
    public String getCategory() {

        return "Login Attempts Security";
    }

    @Override
    public String getSubCategory() {

        return "DEFAULT";
    }

    @Override
    public int getOrder() {

        return 0;
    }

    @Override
    public Map<String, String> getPropertyNameMapping() {

        Map<String, String> nameMapping = new HashMap<>();
        nameMapping.put(SSO_LOGIN_RECAPTCHA_CONNECTOR_NAME + ReCaptchaConnectorPropertySuffixes.ENABLE_ALWAYS,
                "Always prompt reCaptcha");
        nameMapping.put(SSO_LOGIN_RECAPTCHA_CONNECTOR_NAME + ReCaptchaConnectorPropertySuffixes.ENABLE,
                "Prompt reCaptcha after max failed attempts");
        nameMapping.put(SSO_LOGIN_RECAPTCHA_CONNECTOR_NAME + ReCaptchaConnectorPropertySuffixes.MAX_ATTEMPTS,
                "Max failed attempts for reCaptcha");
        return nameMapping;
    }

    @Override
    public Map<String, String> getPropertyDescriptionMapping() {

        Map<String, String> descriptionMapping = new HashMap<>();
        descriptionMapping.put(SSO_LOGIN_RECAPTCHA_CONNECTOR_NAME + ReCaptchaConnectorPropertySuffixes.ENABLE_ALWAYS,
                "Always prompt reCaptcha verification during SSO login flow.");
        descriptionMapping.put(SSO_LOGIN_RECAPTCHA_CONNECTOR_NAME + ReCaptchaConnectorPropertySuffixes.ENABLE,
                "Prompt reCaptcha verification during SSO login flow only after the max failed attempts exceeded.");
        descriptionMapping.put(SSO_LOGIN_RECAPTCHA_CONNECTOR_NAME + ReCaptchaConnectorPropertySuffixes.MAX_ATTEMPTS,
                "Number of failed attempts allowed without prompting reCaptcha verification.");
        return descriptionMapping;
    }

    @Override
    public String[] getPropertyNames() {

        return new String[]{
                SSO_LOGIN_RECAPTCHA_CONNECTOR_NAME + ReCaptchaConnectorPropertySuffixes.ENABLE_ALWAYS,
                SSO_LOGIN_RECAPTCHA_CONNECTOR_NAME + ReCaptchaConnectorPropertySuffixes.ENABLE,
                SSO_LOGIN_RECAPTCHA_CONNECTOR_NAME + ReCaptchaConnectorPropertySuffixes.MAX_ATTEMPTS
        };
    }

    @Override
    public Properties getDefaultPropertyValues(String tenantDomain) throws IdentityGovernanceException {

        String recaptchaEnableAlways = "false";
        String recaptchaEnable = "false";
        String recaptchaMaxAttempts = "3";

        String recaptchaEnableAlwaysProperty = IdentityUtil.getProperty(SSO_LOGIN_RECAPTCHA_ENABLE_ALWAYS);
        String recaptchaEnableProperty = IdentityUtil.getProperty(SSO_LOGIN_RECAPTCHA_ENABLED);
        String recaptchaMaxAttemptsProperty = IdentityUtil.getProperty(SSO_LOGIN_RECAPTCHA_MAX_ATTEMPTS);

        if (StringUtils.isNotEmpty(recaptchaEnableAlwaysProperty)) {
            recaptchaEnableAlways = recaptchaEnableAlwaysProperty;
        }
        if (StringUtils.isNotEmpty(recaptchaEnableProperty)) {
            recaptchaEnable = recaptchaEnableProperty;
        }
        if (StringUtils.isNotEmpty(recaptchaMaxAttemptsProperty)) {
            recaptchaMaxAttempts = recaptchaMaxAttemptsProperty;
        }

        Map<String, String> defaultProperties = CaptchaDataHolder.getInstance()
                .getSSOLoginReCaptchaConnectorPropertyMap();
        if (StringUtils.isBlank(defaultProperties.get(SSO_LOGIN_RECAPTCHA_CONNECTOR_NAME +
                ReCaptchaConnectorPropertySuffixes.ENABLE_ALWAYS))) {
            defaultProperties.put(SSO_LOGIN_RECAPTCHA_CONNECTOR_NAME + ReCaptchaConnectorPropertySuffixes.ENABLE_ALWAYS,
                    recaptchaEnableAlways);
        }
        if (StringUtils.isBlank(defaultProperties.get(SSO_LOGIN_RECAPTCHA_CONNECTOR_NAME +
                ReCaptchaConnectorPropertySuffixes.ENABLE))) {
            defaultProperties.put(SSO_LOGIN_RECAPTCHA_CONNECTOR_NAME + ReCaptchaConnectorPropertySuffixes.ENABLE,
                    recaptchaEnable);
        }
        if (StringUtils.isBlank(defaultProperties.get(SSO_LOGIN_RECAPTCHA_CONNECTOR_NAME +
                ReCaptchaConnectorPropertySuffixes.MAX_ATTEMPTS))) {
            defaultProperties.put(SSO_LOGIN_RECAPTCHA_CONNECTOR_NAME + ReCaptchaConnectorPropertySuffixes.MAX_ATTEMPTS,
                    recaptchaMaxAttempts);
        }

        Properties properties = new Properties();
        properties.putAll(defaultProperties);
        return properties;
    }

    @Override
    public Map<String, String> getDefaultPropertyValues(String[] propertyNames, String tenantDomain)
            throws IdentityGovernanceException {

        return null;
    }

    /**
     * Get tenant from authentication context or username.
     *
     * @param context   Authentication context.
     * @param username  Username.
     * @return          Derived tenant domain.
     */
    private String getTenant(AuthenticationContext context, String username) {

        if (IdentityTenantUtil.isTenantedSessionsEnabled() || IdentityTenantUtil.isTenantQualifiedUrlsEnabled()) {
            return context.getUserTenantDomain();
        } else {
            return MultitenantUtils.getTenantDomain(username);
        }
    }

    /**
     * Check if reCaptcha is enabled for SSO login.
     *
     * @param servletRequest Servlet request.
     * @return true if reCaptcha is enabled for SSO login, false otherwise.
     */
    private boolean isReCaptchaEnabledForSSOLogin(ServletRequest servletRequest) {

        String username = servletRequest.getParameter(USERNAME);
        if (StringUtils.isBlank(username)) {
            return false;
        }

        String sessionDataKey = servletRequest.getParameter(FrameworkUtils.SESSION_DATA_KEY);
        if (sessionDataKey == null) {
            return false;
        }
        AuthenticationContext context = FrameworkUtils.getAuthenticationContextFromCache(sessionDataKey);
        if (context == null) {
            return false;
        }

        String tenantDomain = getTenant(context, username);
        if (StringUtils.isBlank(tenantDomain)) {
            return false;
        }

        Property[] connectorConfigs;
        try {
            connectorConfigs = identityGovernanceService.getConfiguration(new String[]{
                            SSO_LOGIN_RECAPTCHA_CONNECTOR_NAME + ReCaptchaConnectorPropertySuffixes.ENABLE_ALWAYS,
                            SSO_LOGIN_RECAPTCHA_CONNECTOR_NAME + ReCaptchaConnectorPropertySuffixes.ENABLE},
                    tenantDomain);
        } catch (IdentityGovernanceException e) {
            // Can happen due to invalid user/ invalid tenant/ invalid configuration.
            if (log.isDebugEnabled()) {
                log.debug("Unable to load connector configuration.", e);
            }
            return false;
        }

        if (ArrayUtils.isEmpty(connectorConfigs) || connectorConfigs.length != 2 ||
                !(Boolean.parseBoolean(connectorConfigs[0].getValue()) ||
                        Boolean.parseBoolean(connectorConfigs[1].getValue()))) {
            return false;
        }

        if (containsOTPAuthenticatorAttributes(servletRequest) && !isOTPAsFirstFactor(context)) {
            return false;
        }

        return true;
    }

    private boolean containsBasicAuthenticatorAttributes(ServletRequest servletRequest) {

        return servletRequest.getParameter(USERNAME) != null && servletRequest.getParameter(PASSWORD) != null;
    }

    private boolean containsOTPAuthenticatorAttributes(ServletRequest servletRequest) {

        return servletRequest.getParameter(USERNAME) != null && servletRequest.getParameter(OTPCODE) != null;
    }

    private boolean isOTPAsFirstFactor(AuthenticationContext context) {

        return (context.getCurrentStep() == 1 || isPreviousIdPAuthenticationFlowHandler(context));
    }

    /**
     * This method checks if all the authentication steps up to now have been performed by authenticators that
     * implements AuthenticationFlowHandler interface. If so, it returns true.
     * AuthenticationFlowHandlers may not perform actual authentication though the authenticated user is set in the
     * context. Hence, this method can be used to determine if the user has been authenticated by a previous step.
     *
     * @param context   AuthenticationContext.
     * @return True if all the authentication steps up to now have been performed by AuthenticationFlowHandlers.
     */
    private boolean isPreviousIdPAuthenticationFlowHandler(AuthenticationContext context) {

        Map<String, AuthenticatedIdPData> currentAuthenticatedIdPs = context.getCurrentAuthenticatedIdPs();
        return currentAuthenticatedIdPs != null && !currentAuthenticatedIdPs.isEmpty() &&
                currentAuthenticatedIdPs.values().stream().filter(Objects::nonNull)
                        .map(AuthenticatedIdPData::getAuthenticators).filter(Objects::nonNull)
                        .flatMap(List::stream)
                        .allMatch(authenticator ->
                                authenticator.getApplicationAuthenticator() instanceof AuthenticationFlowHandler);
    }
}
