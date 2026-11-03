package org.cibseven.webapp.providers;

import java.util.ArrayList;
import java.util.Map;

import org.cibseven.bpm.engine.IdentityService;
import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.identity.PasswordPolicyResult;
import org.cibseven.bpm.engine.identity.PasswordPolicyRule;
import org.cibseven.bpm.engine.identity.User;
import org.cibseven.bpm.engine.rest.dto.identity.CheckPasswordPolicyRuleDto;
import org.cibseven.webapp.auth.CIBUser;
import org.cibseven.webapp.exception.SystemException;
import org.cibseven.webapp.rest.model.PasswordPolicyRequest;
import org.cibseven.webapp.rest.model.PasswordPolicyResponse;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class DirectIdentityProvider implements IIdentityProvider {

    DirectProviderUtil directProviderUtil;
    public DirectIdentityProvider(DirectProviderUtil directProviderUtil){
        this.directProviderUtil = directProviderUtil;
    }
    @Override
    public PasswordPolicyResponse validatePasswordPolicy(PasswordPolicyRequest request) throws SystemException {
      ProcessEngine processEngine = directProviderUtil.getProcessEngine((CIBUser) null);  
      //TODO User should be specified to the correct engine
        boolean isEnabled = processEngine.getProcessEngineConfiguration().isEnablePasswordPolicy();
        if (!isEnabled) {
            throw new SystemException("Password policy is not enabled for the process engine.");
        }

        IdentityService identityService = processEngine.getIdentityService();

        User user = null;
        PasswordPolicyRequest.Profile profileDto = request.getProfile();
        if (profileDto != null) {
          String id = sanitizeUserId(profileDto.getId());
          user = identityService.newUser(id);

          user.setFirstName(profileDto.getFirstName());
          user.setLastName(profileDto.getLastName());
          user.setEmail(profileDto.getEmail());

        }

        String candidatePassword = request.getPassword();
        PasswordPolicyResult result = identityService.checkPasswordAgainstPolicy(candidatePassword, user);

        PasswordPolicyResponse response = new PasswordPolicyResponse();
        response.setValid(result.isValid());
        response.setRules(new ArrayList<>());

        for (PasswordPolicyRule rule : result.getFulfilledRules()) {
          Map<String, Object> ruleMap = convertRuleToMap(rule, true);
          response.getRules().add(ruleMap);
        }
        for (PasswordPolicyRule rule : result.getViolatedRules()) {
          Map<String, Object> ruleMap = convertRuleToMap(rule, false);
          response.getRules().add(ruleMap);
        }

        return response;
    }
    
    private Map<String, Object> convertRuleToMap(PasswordPolicyRule ruleDto, boolean fulfilled) {
        CheckPasswordPolicyRuleDto rule = new CheckPasswordPolicyRuleDto(ruleDto, fulfilled);
        ObjectMapper objectMapper = directProviderUtil.getObjectMapper((CIBUser) null);
        return objectMapper.convertValue(rule, new TypeReference<Map<String, Object>>() {});
    }

    protected String sanitizeUserId(String userId) {
        return userId != null ? userId : "";
    }
}
