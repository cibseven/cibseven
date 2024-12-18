package org.camunda.bpm.engine.rest.mapper;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;

public class JacksonConfiguratorTest {

    final static int STD_LIMIT = StreamReadConstraints.DEFAULT_MAX_STRING_LEN;
	final static int ACTUAL_LIMIT = 50_000_000;

	@Test
	public void testMaxStringLengthBelow() throws JsonProcessingException {
		final boolean status = test(ACTUAL_LIMIT - 1);
		assertEquals(status, true);
	}

	@Test
	public void testMaxStringLengthExactly() throws JsonProcessingException {
		final boolean status = test(ACTUAL_LIMIT);
		assertEquals(status, true);
	}

	@Test
	public void testMaxStringLengthAbove() throws JsonProcessingException {
		final boolean status = test(ACTUAL_LIMIT + 1);
		assertEquals(status, false);
	}

    @Test
	public void testStdStringLengthBelow() throws JsonProcessingException {
		final boolean status = test(STD_LIMIT - 1);
		assertEquals(status, true);
	}

	@Test
	public void testStdStringLengthExactly() throws JsonProcessingException {
		final boolean status = test(STD_LIMIT);
		assertEquals(status, true);
	}

	@Test
	public void testStdStringLengthAbove() throws JsonProcessingException {
		final boolean status = test(STD_LIMIT + 1);
		assertEquals(status, true);
	}

	public boolean test(final int dataLength) throws JsonProcessingException {

		JacksonConfigurator configurator = new JacksonConfigurator();
		ObjectMapper objectMapper = configurator.getContext(ObjectMapper.class);

		StreamReadConstraints constraints = objectMapper.getFactory().streamReadConstraints();
		assertEquals(ACTUAL_LIMIT, constraints.getMaxStringLength());

		try {
			String longString = new String(new char[dataLength]).replace('\0', 'x');
			String json = objectMapper.writeValueAsString(longString);
			String deserializedString = objectMapper.readValue(json, String.class);
			assertEquals(longString, deserializedString);
			return true;
		}
		catch (Exception ex) {
			return false;
		}
	}
}
