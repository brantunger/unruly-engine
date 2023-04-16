package io.github.brantunger.unruly.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserDetails {
    String firstName;
    String lastName;
    Integer age;
    Long accountNumber;
    Double monthlySalary;
    String bank;
    Integer creditScore;
    Double requestedLoanAmount;
}
