package app.basera.listing;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;

public record ListingRequest(
        @NotBlank @Pattern(regexp = "ROOM|PG|HOSTEL|MESS|TIFFIN|SERVICE") String kind,
        @NotBlank @Size(max = 150) String title,
        @Size(max = 4000) String description,
        @NotBlank @Size(max = 300) String addressLine,
        @Size(max = 100) String locality,
        @NotNull @DecimalMin("-90") @DecimalMax("90") BigDecimal lat,
        @NotNull @DecimalMin("-180") @DecimalMax("180") BigDecimal lng,
        @Pattern(regexp = "^[0-9+\\- ]{7,20}$", message = "digits, +, - and spaces only") String contactPhone,
        @Valid StayInput stay,
        @Valid FoodInput food,
        @Valid UtilityInput utilities) {

    public record StayInput(
            @NotNull @Min(0) Integer rentMonthly,
            @Min(0) Integer deposit,
            @Pattern(regexp = "SINGLE|DOUBLE|TRIPLE|SHARED|FLAT") String roomType,
            @Pattern(regexp = "ANY|MALE|FEMALE") String genderPolicy,
            LocalTime curfewTime,
            Boolean visitorsAllowed,
            Boolean cookingAllowed,
            @Size(max = 1000) String otherRules,
            @Size(max = 30) List<@Size(max = 40) String> amenities,
            Boolean agreementProvided) {}

    public record FoodInput(
            @NotNull @Min(0) Integer monthlyPrice,
            @NotEmpty List<@Pattern(regexp = "VEG|NON_VEG|JAIN") String> dietOptions,
            List<@Pattern(regexp = "BREAKFAST|LUNCH|DINNER") String> mealsIncluded,
            Boolean trialMealAvailable,
            @Min(0) Integer trialMealPrice) {}

    public record UtilityInput(
            @Pattern(regexp = "MUNICIPAL|BOREWELL|TANKER|MIXED|OTHER") String waterSource,
            Boolean roAvailable,
            @Pattern(regexp = "NONE|INVERTER|GENERATOR") String powerBackup,
            Boolean wifiAvailable,
            @Min(0) Integer wifiMbps,
            @Pattern(regexp = "NONE|MACHINE|PAID_SERVICE|MANUAL") String laundry,
            @Size(max = 1000) String notes) {}
}
