package org.cibseven.bpm.engine.rest.mapper;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.fasterxml.jackson.databind.ObjectMapper;

public class JacksonConfiguratorTest {

	final static int DEFAULT_LIMIT = StreamReadConstraints.DEFAULT_MAX_STRING_LEN;
	final static int HIGHER_LIMIT = 50_000_000;

	@Test
	public void testMaxStringLengthBelow() throws JsonProcessingException {
		test(HIGHER_LIMIT - 1);
	}

	@Test
	public void testMaxStringLengthExactly() throws JsonProcessingException {
		test(HIGHER_LIMIT);
	}

	@Test(expected = StreamConstraintsException.class)
	public void testMaxStringLengthAbove() throws JsonProcessingException {
		test(HIGHER_LIMIT + 1);
	}

	@Test
	public void testStdStringLengthBelow() throws JsonProcessingException {
		test(DEFAULT_LIMIT - 1);
	}

	@Test
	public void testStdStringLengthExactly() throws JsonProcessingException {
		test(DEFAULT_LIMIT);
	}

	@Test
	public void testStdStringLengthAbove() throws JsonProcessingException {
		test(DEFAULT_LIMIT + 1);
	}

	public void test(final int dataLength) throws JsonProcessingException {

		JacksonConfigurator configurator = new JacksonConfigurator();
		ObjectMapper objectMapper = configurator.getContext(ObjectMapper.class);

		StreamReadConstraints constraints = objectMapper.getFactory().streamReadConstraints();
		assertEquals(HIGHER_LIMIT, constraints.getMaxStringLength());

		String longString = new String(new char[dataLength]).replace('\0', 'x');
		String json = objectMapper.writeValueAsString(longString);
		String deserializedString = objectMapper.readValue(json, String.class);
		assertEquals(longString, deserializedString);
	}

}
