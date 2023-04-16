package io.github.brantunger.unruly.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "RULES")
@IdClass(RuleDbModel.IdClass.class)
public class RuleDbModel {
    @Id
    @Column(name = "RULE_NAMESPACE")
    private String ruleNamespace;

    @Id
    @Column(name = "RULE_ID")
    private String ruleId;

    @Column(name = "CONDITION")
    private String condition;

    @Column(name = "ACTION")
    private String action;

    @Column(name = "PRIORITY")
    private Integer priority;

    @Column(name = "DESCRIPTION")
    private String description;

    @Data
    static class IdClass implements Serializable {
        private String ruleNamespace;
        private String ruleId;
    }
}
