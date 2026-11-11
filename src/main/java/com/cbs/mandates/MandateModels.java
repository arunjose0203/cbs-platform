package com.cbs.mandates;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;

public final class MandateModels {
    private MandateModels() {}

    /** People recorded against an organization customer. */
    public enum PartyRole { DIRECTOR, BENEFICIAL_OWNER, REPRESENTATIVE }
    public record Party(@NotNull UUID personId,@NotNull PartyRole role,@Pattern(regexp="[0-9]{1,3}(\\.[0-9]{1,2})?") String ownershipPercent) {}
    /** Replaces the complete set of an organization's related persons. */
    public record Parties(@PositiveOrZero long expectedVersion,@NotNull UUID organizationId,@NotNull @Size(min=1,max=50) List<@Valid @NotNull Party> parties,
        @NotBlank @Size(max=500) @Pattern(regexp="[^\\p{Cntrl}]+") String reason) {}
    public record PartyView(UUID personId,String customerNumber,PartyRole role,String ownershipPercent) {}

    /** How many authorised signers must approve a debit. */
    public enum SigningRule { ANY_ONE, ANY_TWO, ALL }
    public enum AccountRole { JOINT_HOLDER, SIGNATORY, GUARDIAN }
    public record AccountParty(@NotNull UUID customerId,@NotNull AccountRole role) {}
    /** Replaces an account's complete mandate: additional parties and signing rule. */
    public record Mandate(@PositiveOrZero long expectedVersion,@NotNull UUID accountId,@NotNull SigningRule rule,@NotNull @Size(max=20) List<@Valid @NotNull AccountParty> parties,
        @NotBlank @Size(max=500) @Pattern(regexp="[^\\p{Cntrl}]+") String reason) {}

    public record Signer(UUID customerId,String customerNumber,String role) {}
    /**
     * Who can authorise debits on an account. {@code required} is false only for an individual
     * adult sole holder with no recorded mandate, whose debits need no signer list.
     */
    public record View(UUID accountId,UUID primaryCustomerId,String primaryType,boolean primaryMinor,boolean configured,boolean required,SigningRule rule,int signaturesNeeded,List<Signer> signers,List<Signer> parties) {}
}
