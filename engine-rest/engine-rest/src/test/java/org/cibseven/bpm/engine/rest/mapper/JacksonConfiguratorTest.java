package org.cibseven.bpm.engine.rest.mapper;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;

public class JacksonConfiguratorTest {

	@Test
	public void testMaxStringLength() throws JsonProcessingException {

		JacksonConfigurator configurator = new JacksonConfigurator();
		ObjectMapper objectMapper = configurator.getContext(ObjectMapper.class);

	        int limit = 50_000_000;

		StreamReadConstraints constraints = objectMapper.getFactory().streamReadConstraints();
		assertEquals(limit, constraints.getMaxStringLength());

		String longString = new String(new char[limit]).replace('\0', 'x');
		String json = objectMapper.writeValueAsString(longString);
		String deserializedString = objectMapper.readValue(json, String.class);
		assertEquals(longString, deserializedString);
	}

}
