package io.micronaut.security.token.jwt.bearer

import io.micronaut.context.annotation.Requires
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.security.authentication.UsernamePasswordCredentials
import io.micronaut.security.testutils.EmbeddedServerSpecification
import io.micronaut.security.testutils.authprovider.MockAuthenticationProvider
import io.micronaut.security.testutils.authprovider.SuccessAuthenticationScenario
import io.micronaut.security.token.jwt.render.BearerAccessRefreshToken
import jakarta.inject.Singleton

class AccessRefreshTokenLoginHandlerValidSpec extends EmbeddedServerSpecification {
    @Override
    String getSpecName() {
        'AccessRefreshTokenLoginHandlerValidSpec'
    }

    @Override
    Map<String, Object> getConfiguration() {
        super.configuration + [
                'micronaut.security.authentication': 'bearer',
                'micronaut.security.token.jwt.signatures.secret.generator.secret': 'qrD6h8K6S9503Q06Y6Rfk21TErImPYqa',
        ]
    }

    void "test valid authentication"() {
        when:
        def creds = new UsernamePasswordCredentials("valid", "valid")
        def resp = client.exchange(HttpRequest.POST('/login', creds), BearerAccessRefreshToken)

        then:
        resp.status == HttpStatus.OK
        resp.body().accessToken
        !resp.body().refreshToken
        resp.body().username == "valid"
        resp.body().roles == ["foo", "bar"]
        resp.body().expiresIn

        when: 'validate json response contains access_token and refresh_token keys as described in RFC6759'
        String json = client.retrieve(HttpRequest.POST('/login', creds), String)

        then:
        json.contains('access_token')
        !json.contains('refresh_token')
    }

    @Singleton
    @Requires(property = 'spec.name', value = 'AccessRefreshTokenLoginHandlerValidSpec')
    static class TestingAuthenticationProvider extends MockAuthenticationProvider {
        TestingAuthenticationProvider() {
            super([new SuccessAuthenticationScenario('valid', ["foo", "bar"])])
        }
    }
}
