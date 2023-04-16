package io.github.brantunger.unruly.repository;

import io.github.brantunger.unruly.model.RuleDbModel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RulesRepository extends JpaRepository<RuleDbModel, Long> {

    List<RuleDbModel> findAll();
}
