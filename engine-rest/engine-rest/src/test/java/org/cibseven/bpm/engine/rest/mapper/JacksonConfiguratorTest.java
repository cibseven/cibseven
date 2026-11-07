package org.cibseven.bpm.engine.rest.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import java.util.Date;
import java.util.TimeZone;

import org.cibseven.bpm.engine.rest.dto.ModificationDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.fasterxml.jackson.databind.ObjectMapper;

public class JacksonConfiguratorTest {

	final static int DEFAULT_LIMIT = StreamReadConstraints.DEFAULT_MAX_STRING_LEN;
	final static int HIGHER_LIMIT = 50_000_000;

	TimeZone previousTimeZone;
	String previousDateFormat;

	@BeforeEach
	public void saveDefaults() {
		previousTimeZone = TimeZone.getDefault();
		previousDateFormat = JacksonConfigurator.dateFormatString;
	}

	@AfterEach
	public void restoreDefaults() {
		TimeZone.setDefault(previousTimeZone);
		JacksonConfigurator.setDateFormatString(previousDateFormat);
	}

	@Test
	public void testMaxStringLengthBelow() throws JsonProcessingException {
		test(HIGHER_LIMIT - 1);
	}

	@Test
	public void testMaxStringLengthExactly() throws JsonProcessingException {
		test(HIGHER_LIMIT);
	}

	@Test
	public void testMaxStringLengthAbove() throws JsonProcessingException {
		assertThrows(StreamConstraintsException.class, () -> {
			test(HIGHER_LIMIT + 1);
    	});
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

	@Test
	public void testDefaultDateFormatUsesJvmTimeZone() throws Exception {
		TimeZone.setDefault(WireFixtures.ZONE);
		assertEquals("\"1970-01-01T01:00:00.000+0100\"", writeDate(new Date(0)));
		assertEquals("\"2013-07-23T14:22:42.123+0200\"", writeDate(new Date(1374582162123L)));

		// as before, a mapper requested after a change of the default time zone uses the new zone
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
		assertEquals("\"1970-01-01T00:00:00.000+0000\"", writeDate(new Date(0)));
	}

	@Test
	public void testCustomDateFormat() throws Exception {
		TimeZone.setDefault(WireFixtures.ZONE);
		JacksonConfigurator.setDateFormatString("yyyy-MM-dd'T'HH:mm:ss");
		assertEquals("\"1970-01-01T01:00:00\"", writeDate(new Date(0)));
		assertEquals(new Date(0), mapper().readValue("\"1970-01-01T01:00:00\"", Date.class));

		JacksonConfigurator.setDateFormatString(JacksonConfigurator.DEFAULT_DATE_FORMAT);
		assertEquals("\"1970-01-01T01:00:00.000+0100\"", writeDate(new Date(0)));
	}

	@Test
	public void testJavaTimeIsWrittenAsIsoString() throws Exception {
		assertEquals("\"2013-01-23\"", mapper().writeValueAsString(LocalDate.of(2013, 1, 23)));
		assertEquals(LocalDate.of(2013, 1, 23), mapper().readValue("\"2013-01-23\"", LocalDate.class));
	}

	@Test
	public void testLenientDeserialization() throws Exception {
		ModificationDto dto = mapper().readValue(
				"{\"skipCustomListeners\":null,\"unknownProperty\":{\"a\":1},\"annotation\":\"x\"} trailing", ModificationDto.class);
		assertFalse(dto.isSkipCustomListeners());
		assertEquals("x", dto.getAnnotation());
	}

	protected String writeDate(Date date) throws Exception {
		return mapper().writeValueAsString(date);
	}

	protected ObjectMapper mapper() {
		return new JacksonConfigurator().getContext(Object.class);
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
