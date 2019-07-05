package org.zstack.sdk;

import java.util.HashMap;
import java.util.Map;
import org.zstack.sdk.*;

public class UpdateFirewallRuleAction extends AbstractAction {

    private static final HashMap<String, Parameter> parameterMap = new HashMap<>();

    private static final HashMap<String, Parameter> nonAPIParameterMap = new HashMap<>();

    public static class Result {
        public ErrorCode error;
        public org.zstack.sdk.UpdateFirewallRuleResult value;

        public Result throwExceptionIfError() {
            if (error != null) {
                throw new ApiException(
                    String.format("error[code: %s, description: %s, details: %s]", error.code, error.description, error.details)
                );
            }
            
            return this;
        }
    }

    @Param(required = true, nonempty = true, nullElements = false, emptyString = true, noTrim = false)
    public java.lang.String vpcFirewallUuid;

    @Param(required = true, nonempty = true, nullElements = false, emptyString = true, noTrim = false)
    public java.lang.String ruleSetUuid;

    @Param(required = true, nonempty = false, nullElements = false, emptyString = true, noTrim = false)
    public java.lang.String uuid;

    @Param(required = true, validValues = {"drop","reject","accept"}, nonempty = false, nullElements = false, emptyString = true, noTrim = false)
    public java.lang.String action;

    @Param(required = false, nonempty = false, nullElements = false, emptyString = true, noTrim = false)
    public java.lang.String protocol;

    @Param(required = false, nonempty = false, nullElements = false, emptyString = true, noTrim = false)
    public java.lang.String destPort;

    @Param(required = false, nonempty = false, nullElements = false, emptyString = true, noTrim = false)
    public java.lang.String sourcePort;

    @Param(required = false, nonempty = false, nullElements = false, emptyString = true, noTrim = false)
    public java.lang.String sourceIp;

    @Param(required = false, nonempty = false, nullElements = false, emptyString = true, noTrim = false)
    public java.lang.String destIp;

    @Param(required = false, nonempty = false, nullElements = false, emptyString = true, noTrim = false)
    public java.lang.String allowStates;

    @Param(required = false, nonempty = false, nullElements = false, emptyString = true, noTrim = false)
    public java.lang.String tcpFlag;

    @Param(required = false, nonempty = false, nullElements = false, emptyString = true, noTrim = false)
    public java.lang.String icmpTypeName;

    @Param(required = true, maxLength = 4, nonempty = false, nullElements = false, emptyString = true, numberRange = {1001L,2999L}, noTrim = false)
    public java.lang.Integer ruleNumber;

    @Param(required = false, nonempty = false, nullElements = false, emptyString = true, noTrim = false)
    public boolean enableLog = false;

    @Param(required = true, validValues = {"enable","disable"}, nonempty = false, nullElements = false, emptyString = true, noTrim = false)
    public java.lang.String state;

    @Param(required = false, nonempty = false, nullElements = false, emptyString = true, noTrim = false)
    public java.lang.String description;

    @Param(required = false)
    public java.util.List systemTags;

    @Param(required = false)
    public java.util.List userTags;

    @Param(required = false)
    public String sessionId;

    @Param(required = false)
    public String accessKeyId;

    @Param(required = false)
    public String accessKeySecret;

    @NonAPIParam
    public long timeout = -1;

    @NonAPIParam
    public long pollingInterval = -1;


    private Result makeResult(ApiResult res) {
        Result ret = new Result();
        if (res.error != null) {
            ret.error = res.error;
            return ret;
        }
        
        org.zstack.sdk.UpdateFirewallRuleResult value = res.getResult(org.zstack.sdk.UpdateFirewallRuleResult.class);
        ret.value = value == null ? new org.zstack.sdk.UpdateFirewallRuleResult() : value; 

        return ret;
    }

    public Result call() {
        ApiResult res = ZSClient.call(this);
        return makeResult(res);
    }

    public void call(final Completion<Result> completion) {
        ZSClient.call(this, new InternalCompletion() {
            @Override
            public void complete(ApiResult res) {
                completion.complete(makeResult(res));
            }
        });
    }

    protected Map<String, Parameter> getParameterMap() {
        return parameterMap;
    }

    protected Map<String, Parameter> getNonAPIParameterMap() {
        return nonAPIParameterMap;
    }

    protected RestInfo getRestInfo() {
        RestInfo info = new RestInfo();
        info.httpMethod = "PUT";
        info.path = "/vpcfirewall/rule/{uuid}";
        info.needSession = true;
        info.needPoll = true;
        info.parameterName = "updateFirewallRule";
        return info;
    }

}
