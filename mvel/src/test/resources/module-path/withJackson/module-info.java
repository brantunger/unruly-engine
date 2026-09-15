module com.example.withjackson {
    requires io.github.brantunger.unruly;
    // Jackson 2, and Jackson 3, which Spring Boot 4 uses. An application needs only one of them.
    requires com.fasterxml.jackson.databind;
    requires tools.jackson.databind;
}
