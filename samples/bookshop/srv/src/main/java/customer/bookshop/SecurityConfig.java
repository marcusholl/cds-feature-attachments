/*
 * © 2024 SAP SE or an SAP affiliate company and cds-feature-attachments contributors.
 */
package customer.bookshop;

import com.sap.cloud.security.spring.config.XsuaaServiceConfiguration;
import com.sap.cloud.security.spring.token.authentication.HybridJwtDecoder;
import com.sap.cloud.security.token.Token;
import com.sap.cloud.security.token.TokenClaims;
import com.sap.cloud.security.token.validation.ValidationResult;
import com.sap.cloud.security.token.validation.ValidationResults;
import com.sap.cloud.security.token.validation.Validator;
import com.sap.cloud.security.token.validation.validators.JwtValidatorBuilder;
import java.util.List;
import org.h2.server.web.JakartaWebServlet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.firewall.HttpFirewall;
import org.springframework.security.web.firewall.StrictHttpFirewall;

@Configuration
public class SecurityConfig {

	@Autowired(required = false)
	private XsuaaServiceConfiguration xsuaaConfig;

	@Bean
	public HttpFirewall allowSemicolonHttpFirewall() {
		StrictHttpFirewall firewall = new StrictHttpFirewall();
		firewall.setAllowSemicolon(true);
		return firewall;
	}

	@Bean
	public ServletRegistrationBean<JakartaWebServlet> h2ServletRegistration() {
		ServletRegistrationBean<JakartaWebServlet> registration =
				new ServletRegistrationBean<>(new JakartaWebServlet(), "/h2-console/*");
		registration.addInitParameter("-webAllowOthers", "");
		return registration;
	}

	/**
	 * Custom JwtDecoder that also trusts tokens issued for the SaaS Registry
	 * (audience: sb-tenant-onboarding!t...) so that the getDependencies callback
	 * at /mt/v1.0/subscriptions/dependencies can be authenticated. The SaaS
	 * Registry token carries the app's mtcallback scope as a cross-app grant.
	 */
	@Bean
	public JwtDecoder jwtDecoder() {
		var xsuaaValidator =
				JwtValidatorBuilder.getInstance(xsuaaConfig)
						.withAudienceValidator(new SaasRegistryAwareAudienceValidator(xsuaaConfig))
						.build();
		return new HybridJwtDecoder(xsuaaValidator, null);
	}

	/**
	 * Audience validator that accepts tokens whose audience contains the app's own
	 * clientid/xsappname OR tokens from the SaaS Registry (audience starts with
	 * "sb-tenant-onboarding"), which carry cross-app granted scopes like mtcallback.
	 */
	static class SaasRegistryAwareAudienceValidator implements Validator<Token> {

		private static final Logger logger =
				LoggerFactory.getLogger(SaasRegistryAwareAudienceValidator.class);

		private final String clientId;
		private final String xsappname;

		SaasRegistryAwareAudienceValidator(XsuaaServiceConfiguration config) {
			this.clientId = config.getClientId();
			this.xsappname = config.getProperty("xsappname");
		}

		@Override
		public ValidationResult validate(Token token) {
			List<String> scopes = token.getClaimAsStringList(TokenClaims.XSUAA.SCOPES);
			logger.info(
					"Validating token: tokenClientId={}, audiences={}, scopes={}",
					token.getClientId(),
					token.getAudiences(),
					scopes);
			for (String aud : token.getAudiences()) {
				if (aud.startsWith("sb-tenant-onboarding")
						|| aud.equals(clientId)
						|| aud.equals(xsappname)) {
					return ValidationResults.createValid();
				}
			}
			return ValidationResults.createInvalid(
					"Jwt token audience {} is not issued for clientId {} or xsappname {}.",
					token.getAudiences(), clientId, xsappname);
		}
	}

}
