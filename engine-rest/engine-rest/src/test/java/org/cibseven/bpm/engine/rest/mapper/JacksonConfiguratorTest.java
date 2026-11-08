package org.cibseven.bpm.engine.rest.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import org.cibseven.bpm.engine.rest.dto.ModificationDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.exc.StreamConstraintsException;
import tools.jackson.databind.ObjectMapper;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

public class JacksonConfiguratorTest {

	final static int DEFAULT_LIMIT = JacksonConfigurator.DEFAULT_MAX_STRING_LENGTH;
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
	public void testMaxStringLengthBelow() {
		test(HIGHER_LIMIT - 1);
	}

	@Test
	public void testMaxStringLengthExactly() {
		test(HIGHER_LIMIT);
	}

	@Test
	public void testMaxStringLengthAbove() {
		assertThrows(StreamConstraintsException.class, () -> {
			test(HIGHER_LIMIT + 1);
    	});
	}

	@Test
	public void testStdStringLengthBelow() {
		test(DEFAULT_LIMIT - 1);
	}

	@Test
	public void testStdStringLengthExactly() {
		test(DEFAULT_LIMIT);
	}

	@Test
	public void testStdStringLengthAbove() {
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

	public void test(final int dataLength) {

		JacksonConfigurator configurator = new JacksonConfigurator();
		ObjectMapper objectMapper = configurator.getContext(ObjectMapper.class);

		StreamReadConstraints constraints = objectMapper.tokenStreamFactory().streamReadConstraints();
		assertEquals(HIGHER_LIMIT, constraints.getMaxStringLength());

		String longString = new String(new char[dataLength]).replace('\0', 'x');
		String json = objectMapper.writeValueAsString(longString);
		String deserializedString = objectMapper.readValue(json, String.class);
		assertEquals(longString, deserializedString);
	}

	@Test
	public void testLocaleWrittenLikeJackson2() throws Exception {
		// Jackson 3 alone writes the language tag "en-US"
		ObjectMapper mapper = JacksonConfigurator.configureObjectMapper();

		Map<String, Locale> locales = new LinkedHashMap<>();
		locales.put("locale", Locale.US);
		locales.put("root", Locale.ROOT);
		assertEquals("{\"locale\":\"en_US\",\"root\":\"\"}", mapper.writeValueAsString(locales));
		assertEquals(Locale.US, mapper.readValue("\"en_US\"", Locale.class));
		assertEquals(Locale.US, mapper.readValue("\"en-US\"", Locale.class));
	}

	public static class PolymorphicLocale {
		@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS)
		public Object value = Locale.US;
	}

	public static class NonEmptyLocales {
		@JsonInclude(JsonInclude.Include.NON_EMPTY)
		public Locale root = Locale.ROOT;
		@JsonInclude(JsonInclude.Include.NON_EMPTY)
		public Locale locale = Locale.US;
	}

	@Test
	public void testLocaleWithTypeInformationWrittenLikeJackson2() throws Exception {
		ObjectMapper mapper = JacksonConfigurator.configureObjectMapper();

		assertEquals("{\"value\":[\"java.util.Locale\",\"en_US\"]}", mapper.writeValueAsString(new PolymorphicLocale()));
	}

	@Test
	public void testRootLocaleSkippedAsEmptyLikeJackson2() throws Exception {
		// Locale.ROOT is written as "" and counts as empty, so NON_EMPTY leaves it out
		ObjectMapper mapper = JacksonConfigurator.configureObjectMapper();

		assertEquals("{\"locale\":\"en_US\"}", mapper.writeValueAsString(new NonEmptyLocales()));
	}

	/** Accessors whose property names Jackson 3 builds differently or not at all. */
	public static class Accessors {
		private String url = "u";
		private String urlValue = "uv";
		private int x = 1;
		private int xCoordinate = 2;
		private boolean ok = true;
		private String iban = "i";
		private String abc = "abc";
		private String links = "l";
		private String value = "v";
		private String first = "f";
		private String a1 = "a1";

		public String getURL() { return url; }
		public void setURL(String url) { this.url = url; }
		public String getURLValue() { return urlValue; }
		public void setURLValue(String urlValue) { this.urlValue = urlValue; }
		public int getX() { return x; }
		public void setX(int x) { this.x = x; }
		public int getXCoordinate() { return xCoordinate; }
		public void setXCoordinate(int xCoordinate) { this.xCoordinate = xCoordinate; }
		public boolean isOK() { return ok; }
		public void setOK(boolean ok) { this.ok = ok; }
		public String getIBAN() { return iban; }
		public void setIBAN(String iban) { this.iban = iban; }
		public String getaBc() { return abc; }
		public void setaBc(String abc) { this.abc = abc; }
		public String get_links() { return links; }
		public void set_links(String links) { this.links = links; }
		public String getvalue() { return value; }
		public void setvalue(String value) { this.value = value; }
		public String get1st() { return first; }
		public void set1st(String first) { this.first = first; }
		public String getA1() { return a1; }
		public void setA1(String a1) { this.a1 = a1; }
	}

	/** Every property has a field of the same name (Jackson 2 naming): the field order is the property order. */
	public static class FieldOrder {
		private String url = "u";
		private boolean ok = true;
		private String iban = "i";
		private int x = 1;

		public String getURL() { return url; }
		public boolean isOK() { return ok; }
		public String getIBAN() { return iban; }
		public int getX() { return x; }
	}

	/** A record with additional getters; Jackson 3 names getURL() like the component URL. */
	public record RecordAccessors(String URL, String getValue) {
		public String getURL() { return "extra"; }
		public boolean isOK() { return true; }
	}

	/** What Jackson 2 writes for {@code new RecordAccessors("u", "v")}. */
	protected static final String RECORD_ACCESSORS_JSON = "{\"URL\":\"u\",\"getValue\":\"v\",\"url\":\"extra\",\"ok\":true}";

	public static class VoidGetter {
		public int getValue() { return 1; }
		public Void getNothing() { return null; }
	}

	public static class LocaleValue {
		public Locale locale;
		public Map<Locale, Integer> keys = new LinkedHashMap<>();
	}

	/** What Jackson 2 writes for {@link Accessors}; the order of the properties without a field is not fixed. */
	protected static final String ACCESSORS_JSON = "{\"url\":\"u\",\"x\":1,\"ok\":true,\"iban\":\"i\",\"value\":\"v\",\"a1\":\"a1\","
			+ "\"urlvalue\":\"uv\",\"xcoordinate\":2,\"aBc\":\"abc\",\"_links\":\"l\",\"1st\":\"f\"}";

	/** Locales whose {@link Locale#toString()} has a script or extensions ("zh_TW_#Hant"). */
	protected static final List<String> LOCALES_WITH_SCRIPT_OR_EXTENSIONS = List.of("zh-Hant-TW", "sr-Latn-RS",
			"th-TH-u-nu-thai", "en-Latn-US-u-ca-japanese", "ja-JP-u-ca-japanese-x-lvariant-JP");

	@Test
	public void testAccessorsNamedLikeJackson2() throws Exception {
		// e.g. deserialized object variable values
		ObjectMapper mapper = JacksonConfigurator.configureObjectMapper();

		// Jackson 3 alone writes {"x":1,"a1":"a1","URL":"u","URLValue":"uv","XCoordinate":2,"OK":true,"IBAN":"i"}
		assertEquals(mapper.readTree(ACCESSORS_JSON), mapper.readTree(mapper.writeValueAsString(new Accessors())));
		// Jackson 3 alone writes {"x":1,"URL":"u","OK":true,"IBAN":"i"}
		assertEquals("{\"url\":\"u\",\"ok\":true,\"iban\":\"i\",\"x\":1}", mapper.writeValueAsString(new FieldOrder()));
		Accessors accessors = mapper.readValue(ACCESSORS_JSON.replace("\"u\"", "\"U\"").replace("\"l\"", "\"L\""), Accessors.class);
		assertEquals("U", accessors.getURL());
		assertEquals("L", accessors.get_links());
	}

	@Test
	public void testAdditionalRecordGettersNamedLikeJackson2() throws Exception {
		// Jackson 3 alone writes {"URL":"extra","getValue":"v","OK":true}: the component's value is lost
		ObjectMapper mapper = JacksonConfigurator.configureObjectMapper();

		assertEquals(mapper.readTree(RECORD_ACCESSORS_JSON), mapper.readTree(mapper.writeValueAsString(new RecordAccessors("u", "v"))));
		assertEquals(new RecordAccessors("u", "v"), mapper.readValue("{\"URL\":\"u\",\"getValue\":\"v\"}", RecordAccessors.class));
	}

	@Test
	public void testVoidGettersNotWrittenLikeJackson2() throws Exception {
		// Jackson 3 alone writes {"value":1,"nothing":null}
		assertEquals("{\"value\":1}", JacksonConfigurator.configureObjectMapper().writeValueAsString(new VoidGetter()));
	}

	@Test
	public void testLocalesWithScriptOrExtensionsReadLikeJackson2() throws Exception {
		ObjectMapper mapper = JacksonConfigurator.configureObjectMapper();
		for (String tag : LOCALES_WITH_SCRIPT_OR_EXTENSIONS) {
			LocaleValue value = new LocaleValue();
			value.locale = Locale.forLanguageTag(tag);
			value.keys.put(value.locale, 1);

			// Jackson 3 alone reads "zh_TW_#Hant" as zh-TW
			LocaleValue readBack = mapper.readValue(mapper.writeValueAsString(value), LocaleValue.class);

			assertEquals(value.locale, readBack.locale, tag);
			assertEquals(value.keys, readBack.keys, tag);
		}
	}

	public static class DateTimeValues {
		public java.time.Year year = java.time.Year.of(2020);
		@JsonFormat(shape = JsonFormat.Shape.NUMBER)
		public java.time.Year yearAsNumber = java.time.Year.of(2020);
		public java.time.Month month = java.time.Month.MARCH;
		public Map<java.time.Month, Integer> monthKeys = new LinkedHashMap<>(Map.of(java.time.Month.JANUARY, 1));
		public java.sql.Date sqlDate = new java.sql.Date(0);
		@JsonFormat(pattern = "dd.MM.yyyy", timezone = "UTC")
		public java.sql.Date formattedSqlDate = new java.sql.Date(0);
	}

	@Test
	public void testYearMonthAndSqlDateWrittenLikeJackson2() throws Exception {
		// Jackson 3 alone writes {"year":2020,...,"month":2,...,"sqlDate":"1969-12-31T19:00:00.000-0500",...}
		TimeZone previous = TimeZone.getDefault();
		TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
		try {
			ObjectMapper mapper = JacksonConfigurator.configureObjectMapper();

			assertEquals("{\"year\":\"2020\",\"yearAsNumber\":2020,\"month\":\"MARCH\",\"monthKeys\":{\"JANUARY\":1},"
					+ "\"sqlDate\":\"1969-12-31\",\"formattedSqlDate\":\"01.01.1970\"}", mapper.writeValueAsString(new DateTimeValues()));
			DateTimeValues readBack = mapper.readValue("{\"year\":\"2020\",\"month\":\"MARCH\",\"monthKeys\":{\"JANUARY\":1}}", DateTimeValues.class);
			assertEquals(java.time.Month.MARCH, readBack.month);
			assertEquals(Map.of(java.time.Month.JANUARY, 1), readBack.monthKeys);
		} finally {
			TimeZone.setDefault(previous);
		}
	}
}
