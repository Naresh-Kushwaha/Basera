package app.basera.tenancy;

import app.basera.config.BaseraProperties;
import org.springframework.stereotype.Component;

/** Builds absolute URLs for tenant hosts: <scheme>://<slug>.<base-domain><port-suffix>. */
@Component
public class TenantUrls {

    private final BaseraProperties.Tenant props;

    public TenantUrls(BaseraProperties props) {
        this.props = props.tenant();
    }

    public String tenantBase(String slug) {
        return props.urlScheme() + "://" + slug + "." + props.baseDomain() + props.publicPortSuffix();
    }

    /** Central login host. Its callback URL is the ONLY redirect URI registered with Google. */
    public String authBase() {
        return tenantBase("auth");
    }

    public String platformBase() {
        return tenantBase("admin");
    }
}
