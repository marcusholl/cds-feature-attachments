/*
 * © 2024 SAP SE or an SAP affiliate company and cds-feature-attachments contributors.
 */
package customer.bookshop;

import com.sap.cds.services.request.ModifiableUserInfo;
import com.sap.cds.services.request.UserInfo;
import com.sap.cds.services.runtime.CdsRuntimeConfiguration;
import com.sap.cds.services.runtime.CdsRuntimeConfigurer;
import com.sap.cds.services.runtime.UserInfoProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

public class SaasRegistryRuntimeConfig implements CdsRuntimeConfiguration {

	@Override
	public int order() {
		return Integer.MAX_VALUE;
	}

	@Override
	public void providers(CdsRuntimeConfigurer configurer) {
		configurer.provider(new SaasRegistryUserInfoProvider());
	}

	static class SaasRegistryUserInfoProvider implements UserInfoProvider {

		private static final Logger logger =
				LoggerFactory.getLogger(SaasRegistryUserInfoProvider.class);

		private static final String SAAS_REGISTRY_CLIENT_ID_PREFIX = "sb-tenant-onboarding";
		private static final String MTCALLBACK_ROLE = "mtcallback";

		private UserInfoProvider previous;

		@Override
		public void setPrevious(UserInfoProvider previous) {
			this.previous = previous;
		}

		@Override
		public UserInfo get() {
			UserInfo userInfo = previous != null ? previous.get() : null;

			Authentication auth = SecurityContextHolder.getContext().getAuthentication();
			if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
				return userInfo;
			}

			String clientId = jwt.getClaimAsString("client_id");
			if (clientId == null) {
				clientId = jwt.getClaimAsString("cid");
			}

			if (clientId == null || !clientId.startsWith(SAAS_REGISTRY_CLIENT_ID_PREFIX)) {
				return userInfo;
			}

			logger.info(
					"SaaS Registry token detected (clientId={}), granting role '{}'",
					clientId,
					MTCALLBACK_ROLE);

			ModifiableUserInfo modifiable = userInfo != null ? userInfo.copy() : UserInfo.create();
			modifiable.addRole(MTCALLBACK_ROLE);
			return modifiable;
		}
	}

}
