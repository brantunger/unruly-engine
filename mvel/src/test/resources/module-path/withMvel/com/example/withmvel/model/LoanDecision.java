package com.example.withmvel.model;

public class LoanDecision {

    private boolean approved;
    private double interestRate;

    // The package is exported, so javac's missing-explicit-ctor lint wants the constructor spelled out.
    public LoanDecision() {
    }

    public boolean isApproved() {
        return approved;
    }

    public void setApproved(boolean approved) {
        this.approved = approved;
    }

    public double getInterestRate() {
        return interestRate;
    }

    public void setInterestRate(double interestRate) {
        this.interestRate = interestRate;
    }

    @Override
    public String toString() {
        return "LoanDecision[approved=" + approved + ", interestRate=" + interestRate + "]";
    }
}
