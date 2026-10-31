package app.basera.platform.dto;

import app.basera.tenancy.TenantInfo;

public record CreateTenantResponse(TenantInfo tenant, InvitationSummary adminInvitation) {}
