/*
 * =============================================================================
 * 
 *   Copyright (c) 2011-2014, The THYMELEAF team (http://www.thymeleaf.org)
 * 
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 * 
 *       http://www.apache.org/licenses/LICENSE-2.0
 * 
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 * 
 * =============================================================================
 */
package org.thymeleaf.context;

import java.util.Locale;
import java.util.Map;

import org.thymeleaf.IEngineConfiguration;
import org.thymeleaf.engine.StandardModelFactory;
import org.thymeleaf.model.IModelFactory;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.TemplateResolution;
import org.thymeleaf.util.Validate;

/**
 *
 * @author Daniel Fern&aacute;ndez
 * @since 3.0.0
 * 
 */
public abstract class AbstractTemplateProcessingContext
            extends AbstractProcessingContext
            implements ITemplateProcessingContext {

    private final TemplateResolution templateResolution;
    private final TemplateMode templateMode;
    private final IModelFactory modelFactory;
    private final IdentifierSequences identifierSequences;



    public AbstractTemplateProcessingContext(
            final IEngineConfiguration configuration,
            final TemplateResolution templateResolution,
            final IContext context) {

        super(configuration, context);

        Validate.notNull(templateResolution, "Template Resolution object cannot be null");

        this.templateResolution = templateResolution;
        this.templateMode = this.templateResolution.getTemplateMode();
        this.modelFactory =
                new StandardModelFactory(
                        this.templateMode, getConfiguration().getTextRepository(),
                        getConfiguration().getAttributeDefinitions(), getConfiguration().getElementDefinitions());
        this.identifierSequences = new IdentifierSequences();

    }

    public AbstractTemplateProcessingContext(
            final IEngineConfiguration configuration,
            final TemplateResolution templateResolution,
            final Locale locale, final Map<String, Object> variables) {

        super(configuration, locale, variables);

        Validate.notNull(templateResolution, "Template Resolution object cannot be null");

        this.templateResolution = templateResolution;
        this.templateMode = this.templateResolution.getTemplateMode();
        this.modelFactory =
                new StandardModelFactory(
                        this.templateMode, getConfiguration().getTextRepository(),
                        getConfiguration().getAttributeDefinitions(), getConfiguration().getElementDefinitions());
        this.identifierSequences = new IdentifierSequences();

    }



    public final TemplateResolution getTemplateResolution() {
        return this.templateResolution;
    }

    public final TemplateMode getTemplateMode() {
        return this.templateMode;
    }

    public final IModelFactory getModelFactory() {
        return this.modelFactory;
    }

    public IdentifierSequences getIdentifierSequences() {
        return this.identifierSequences;
    }

}