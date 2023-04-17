package io.github.brantunger.unruly.service;

import io.github.brantunger.unruly.model.Rule;
import io.github.brantunger.unruly.model.RuleDbModel;
import io.github.brantunger.unruly.repository.RulesRepository;
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


    private Rule mapFromDbModel(RuleDbModel ruleDbModel) {
        return Rule.builder()
                .ruleId(ruleDbModel.getRuleId())
                .condition(ruleDbModel.getCondition())
                .action(ruleDbModel.getAction())
                .description(ruleDbModel.getDescription())
                .priority(ruleDbModel.getPriority())
                .build();
    }
}