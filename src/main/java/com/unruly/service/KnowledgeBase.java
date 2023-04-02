package com.unruly.service;

import com.unruly.model.Rule;
import com.unruly.model.RuleDbModel;
import com.unruly.model.RuleNamespace;
import com.unruly.repository.RulesRepository;
import com.google.common.base.Enums;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class KnowledgeBase {

    private final RulesRepository rulesRepository;

    public KnowledgeBase(RulesRepository rulesRepository) {
        this.rulesRepository = rulesRepository;
    }

    public List<Rule> getAllRules() {
        return rulesRepository.findAll().stream()
                .map(this::mapFromDbModel)
                .collect(Collectors.toList());
    }

    public List<Rule> getAllRuleByNamespace(String ruleNamespace) {
        return rulesRepository.findByRuleNamespace(ruleNamespace).stream()
                .map(this::mapFromDbModel)
                .collect(Collectors.toList());
    }

    private Rule mapFromDbModel(RuleDbModel ruleDbModel) {
        RuleNamespace namespace = Enums.getIfPresent(RuleNamespace.class, ruleDbModel.getRuleNamespace().toUpperCase())
                .or(RuleNamespace.DEFAULT);
        return Rule.builder()
                .ruleNamespace(namespace)
                .ruleId(ruleDbModel.getRuleId())
                .condition(ruleDbModel.getCondition())
                .action(ruleDbModel.getAction())
                .description(ruleDbModel.getDescription())
                .priority(ruleDbModel.getPriority())
                .build();
    }
}
