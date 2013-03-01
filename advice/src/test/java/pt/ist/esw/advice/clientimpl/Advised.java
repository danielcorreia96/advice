/*
 * AtomicAnnotation
 * Copyright (C) 2012 INESC-ID Software Engineering Group
 * http://www.esw.inesc-id.pt
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
 *
 * Author's contact:
 * INESC-ID Software Engineering Group
 * Rua Alves Redol 9
 * 1000 - 029 Lisboa
 * Portugal
 */
package pt.ist.esw.advice.clientimpl;

import java.lang.annotation.ElementType;
import java.lang.annotation.Target;

import pt.ist.esw.advice.AdviceFactory;
import pt.ist.esw.advice.NullContextFactory;

@Target(ElementType.METHOD)
public @interface Advised {
    /**
     * Default AdviceFactory used, when none is specified in the annotation.
     * It is recommended that clients provide this class.
     **/
    static final String DEFAULT_CONTEXT_FACTORY = "pt.ist.esw.advice.clientimpl.DefaultContextFactory";

    boolean readOnly() default false;

    boolean canFail() default true;

    boolean speculativeReadOnly() default true;

    Class<? extends AdviceFactory> adviceFactory() default NullContextFactory.class;
}
