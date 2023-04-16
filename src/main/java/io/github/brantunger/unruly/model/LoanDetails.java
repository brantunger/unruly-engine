package io.github.brantunger.unruly.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashSet;
import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoanDetails {
    Long accountNumber;
    Boolean approvalStatus;
    Float interestRate;
    Float sanctionedPercentage;
    Double processingFees;
    @Builder.Default
    Set<String> testSet = new HashSet<>();
}
