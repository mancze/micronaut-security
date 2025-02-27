package io.micronaut.security.filters

import io.micronaut.context.ApplicationContext
import io.micronaut.core.util.StringUtils
import io.micronaut.security.testutils.ApplicationContextSpecification
import spock.lang.Specification

class SecurityFilterDisabledSpec extends ApplicationContextSpecification {

    @Override
    Map<String, Object> getConfiguration() {
        super.configuration + [
                'micronaut.security.filter.enabled': StringUtils.FALSE
        ]
    }

    void "it is possible to disable the SecurityFilter with micronaut.security.filter.enabled = false"() {
        expect:
        !applicationContext.containsBean(SecurityFilter.class)
    }
}
