package org.camunda.bpm.engine.test.cache.listener;

import org.camunda.bpm.engine.test.cache.event.ProcessEngineEvent;

public interface ProcessEngineEventListener<T extends ProcessEngineEvent> {

    void onEvent(T event);

}