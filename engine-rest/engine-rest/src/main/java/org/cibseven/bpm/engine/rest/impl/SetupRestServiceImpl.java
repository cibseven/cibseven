package org.cibseven.bpm.engine.rest.impl;

import java.util.function.Supplier;

import org.cibseven.bpm.engine.IdentityService;
import org.cibseven.bpm.engine.authorization.Groups;
import org.cibseven.bpm.engine.impl.identity.Authentication;
import org.cibseven.bpm.engine.rest.SetupRestService;

import com.fasterxml.jackson.databind.ObjectMapper;

public class SetupRestServiceImpl extends AbstractRestProcessEngineAware  implements SetupRestService {

  public SetupRestServiceImpl(String processEngineName, ObjectMapper objectMapper) {
    super(processEngineName, objectMapper);
  }

  @Override
  public boolean requiresSetup() {
    Boolean isNoAdminGroup = runWithoutAuthorization(() -> {
        return hasNoCamundaAdminGroup();
    });
    return isNoAdminGroup;
  }

  private <V extends Object> V runWithoutAuthorization(Supplier<V> action) {
    IdentityService identityService = getProcessEngine().getIdentityService();
    Authentication currentAuthentication = identityService.getCurrentAuthentication();
    try {
      identityService.clearAuthentication();
      return action.get();
    } catch (Exception e) {
      throw e;
    } finally {
      identityService.setAuthentication(currentAuthentication);
    }
  }

  private boolean hasNoCamundaAdminGroup() {
    final IdentityService identityService = getProcessEngine().getIdentityService();
    long groupCount = identityService.createGroupQuery().groupId(Groups.CAMUNDA_ADMIN).count();
    return groupCount == 0;
  }

}
