package com.childcarewow.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class DatasourceConfigIT {

  @Autowired
  JdbcTemplate calendarJdbcTemplate;

  @Autowired
  @Qualifier("platformJdbcTemplate")
  JdbcTemplate platformJdbcTemplate;

  @Test
  void calendarDatasourceConnects() {
    Integer one = calendarJdbcTemplate.queryForObject("SELECT 1", Integer.class);
    assertThat(one).isEqualTo(1);
  }

  @Test
  void platformDatasourceHasSeedUsers() {
    Long count = platformJdbcTemplate.queryForObject("SELECT count(*) FROM users", Long.class);
    assertThat(count).isEqualTo(7L);
  }
}
