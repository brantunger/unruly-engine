package com.example.withoutmvel.model;

/** A fact the rules read properties of, which the engine reads for the language with {@code FactProperties}. */
public record Applicant(String name, boolean prime, boolean member) {
}
