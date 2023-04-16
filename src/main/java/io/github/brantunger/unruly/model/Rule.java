package io.github.brantunger.unruly.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Rule {
    String ruleId;
    String condition;
    String action;
    Integer priority;
    String description;
}
