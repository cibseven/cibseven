/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. Camunda licenses this file to you under the Apache License,
 * Version 2.0; you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.cibseven.bpm.engine.impl.json;

import com.google.gson.JsonObject;
import org.cibseven.bpm.engine.impl.cmd.AbstractProcessInstanceModificationCommand;
import org.cibseven.bpm.engine.impl.cmd.ActivityAfterInstantiationCmd;
import org.cibseven.bpm.engine.impl.cmd.ActivityBeforeInstantiationCmd;
import org.cibseven.bpm.engine.impl.cmd.ActivityCancellationCmd;
import org.cibseven.bpm.engine.impl.cmd.ActivityInstanceCancellationCmd;
import org.cibseven.bpm.engine.impl.cmd.TransitionInstanceCancellationCmd;
import org.cibseven.bpm.engine.impl.cmd.TransitionInstantiationCmd;
import org.cibseven.bpm.engine.impl.util.JsonUtil;

public class ModificationCmdJsonConverter extends JsonObjectConverter<AbstractProcessInstanceModificationCommand> {

  public static final ModificationCmdJsonConverter INSTANCE = new ModificationCmdJsonConverter();

  public static final String START_BEFORE = "startBeforeActivity";
  public static final String START_AFTER = "startAfterActivity";
  public static final String START_TRANSITION = "startTransition";
  public static final String CANCEL_ALL = "cancelAllForActivity";
  public static final String CANCEL_CURRENT = "cancelCurrentActiveActivityInstances";
  public static final String CANCEL_ACTIVITY_INSTANCES = "cancelActivityInstances";
  public static final String PROCESS_INSTANCE = "processInstances";
  public static final String CANCEL_TRANSITION_INSTANCES = "cancelTransitionInstances";
  public static final String ANCESTOR_ACTIVITY_INSTANCE_ID = "ancestorActivityInstanceId";

  @Override
  public JsonObject toJsonObject(AbstractProcessInstanceModificationCommand command) {
    JsonObject json = JsonUtil.createObject();

    if (command instanceof ActivityAfterInstantiationCmd) {
      JsonUtil.addField(json, START_AFTER, ((ActivityAfterInstantiationCmd) command).getTargetElementId());
      JsonUtil.addField(json, ANCESTOR_ACTIVITY_INSTANCE_ID,
          ((ActivityAfterInstantiationCmd) command).getAncestorActivityInstanceId());
    } else if (command instanceof ActivityBeforeInstantiationCmd) {
      JsonUtil.addField(json, START_BEFORE, ((ActivityBeforeInstantiationCmd) command).getTargetElementId());
      JsonUtil.addField(json, ANCESTOR_ACTIVITY_INSTANCE_ID,
          ((ActivityBeforeInstantiationCmd) command).getAncestorActivityInstanceId());
    } else if (command instanceof TransitionInstantiationCmd) {
      JsonUtil.addField(json, START_TRANSITION, ((TransitionInstantiationCmd) command).getTargetElementId());
    } else if (command instanceof ActivityCancellationCmd) {
      JsonUtil.addField(json, CANCEL_ALL, ((ActivityCancellationCmd) command).getActivityId());
      JsonUtil.addField(json, CANCEL_CURRENT,
          ((ActivityCancellationCmd) command).isCancelCurrentActiveActivityInstances());
    } else if (command instanceof ActivityInstanceCancellationCmd) {
      JsonUtil.addField(json, CANCEL_ACTIVITY_INSTANCES,
          ((ActivityInstanceCancellationCmd) command).getActivityInstanceId());
      JsonUtil.addField(json, PROCESS_INSTANCE, ((ActivityInstanceCancellationCmd) command).getProcessInstanceId());
    } else if (command instanceof TransitionInstanceCancellationCmd) {
      JsonUtil.addField(json, CANCEL_TRANSITION_INSTANCES,
          ((TransitionInstanceCancellationCmd) command).getTransitionInstanceId());
      JsonUtil.addField(json, PROCESS_INSTANCE, ((TransitionInstanceCancellationCmd) command).getProcessInstanceId());
    }

    return json;
  }

  @Override
  public AbstractProcessInstanceModificationCommand toObject(JsonObject json) {

    AbstractProcessInstanceModificationCommand cmd = null;

    if (json.has(START_BEFORE)) {
      cmd = new ActivityBeforeInstantiationCmd(JsonUtil.getString(json, START_BEFORE));
      if (json.has(ANCESTOR_ACTIVITY_INSTANCE_ID)) {
        ((ActivityBeforeInstantiationCmd) cmd).setAncestorActivityInstanceId(
            JsonUtil.getString(json, ANCESTOR_ACTIVITY_INSTANCE_ID));
      }
    } else if (json.has(START_AFTER)) {
      cmd = new ActivityAfterInstantiationCmd(JsonUtil.getString(json, START_AFTER));
      if (json.has(ANCESTOR_ACTIVITY_INSTANCE_ID)) {
        ((ActivityAfterInstantiationCmd) cmd).setAncestorActivityInstanceId(
            JsonUtil.getString(json, ANCESTOR_ACTIVITY_INSTANCE_ID));
      }
    } else if (json.has(START_TRANSITION)) {
      cmd = new TransitionInstantiationCmd(JsonUtil.getString(json, START_TRANSITION));
    } else if (json.has(CANCEL_ALL)) {
      cmd = new ActivityCancellationCmd(JsonUtil.getString(json, CANCEL_ALL));
      boolean cancelCurrentActiveActivityInstances = JsonUtil.getBoolean(json, CANCEL_CURRENT);
      ((ActivityCancellationCmd) cmd).setCancelCurrentActiveActivityInstances(cancelCurrentActiveActivityInstances);
    } else if (json.has(CANCEL_ACTIVITY_INSTANCES)) {
      cmd = new ActivityInstanceCancellationCmd(JsonUtil.getString(json, PROCESS_INSTANCE),
          JsonUtil.getString(json, CANCEL_ACTIVITY_INSTANCES));
    } else if (json.has(CANCEL_TRANSITION_INSTANCES)) {
      cmd = new TransitionInstanceCancellationCmd(JsonUtil.getString(json, PROCESS_INSTANCE),
          JsonUtil.getString(json, CANCEL_TRANSITION_INSTANCES));
    }

    return cmd;
  }

}
