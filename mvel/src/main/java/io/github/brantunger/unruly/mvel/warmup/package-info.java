/**
 * What {@link io.github.brantunger.unruly.mvel.MvelExpressionLanguage#prepare()} evaluates MVEL on, so that a JVM's
 * first run has fewer of the classes MVEL evaluates with left to load. Not part of the API: the module exports this
 * package only to MVEL, which reads it by reflection, and the class here is public only so that MVEL can.
 */
@NullMarked
package io.github.brantunger.unruly.mvel.warmup;

import org.jspecify.annotations.NullMarked;
