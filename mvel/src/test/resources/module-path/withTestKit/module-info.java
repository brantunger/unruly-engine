module com.example.withtestkit {
    requires io.github.brantunger.unruly;
    requires io.github.brantunger.unruly.test;
    requires org.junit.platform.launcher;

    // JUnit runs the contract test by reflection.
    opens com.example.withtestkit to org.junit.platform.commons;
}
