package com.url.shortly;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.url.shortly.config.AppProperties;

@SpringBootTest
@ActiveProfiles("test")
class ShortlyApplicationTests {

	@Autowired
	private AppProperties appProperties;

	@Test
	void contextLoads() {
		assertThat(appProperties).isNotNull();
		assertThat(appProperties.shortCode().length()).isEqualTo(7);
		assertThat(appProperties.security().jwt().secret()).isNotBlank();
	}

}
