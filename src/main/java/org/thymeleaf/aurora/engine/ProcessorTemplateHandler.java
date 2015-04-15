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
package org.thymeleaf.aurora.engine;

import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.thymeleaf.aurora.ITemplateEngineConfiguration;
import org.thymeleaf.aurora.context.ITemplateProcessingContext;
import org.thymeleaf.aurora.context.IVariablesMap;
import org.thymeleaf.aurora.model.IAutoCloseElementTag;
import org.thymeleaf.aurora.model.IAutoOpenElementTag;
import org.thymeleaf.aurora.model.ICDATASection;
import org.thymeleaf.aurora.model.ICloseElementTag;
import org.thymeleaf.aurora.model.IComment;
import org.thymeleaf.aurora.model.IDocType;
import org.thymeleaf.aurora.model.IOpenElementTag;
import org.thymeleaf.aurora.model.IProcessingInstruction;
import org.thymeleaf.aurora.model.IStandaloneElementTag;
import org.thymeleaf.aurora.model.IText;
import org.thymeleaf.aurora.model.IUnmatchedCloseElementTag;
import org.thymeleaf.aurora.model.IXMLDeclaration;
import org.thymeleaf.aurora.processor.IProcessor;
import org.thymeleaf.aurora.processor.element.IElementProcessor;
import org.thymeleaf.aurora.processor.node.INodeProcessor;
import org.thymeleaf.aurora.templatemode.TemplateMode;
import org.thymeleaf.exceptions.TemplateProcessingException;
import org.thymeleaf.util.StringUtils;
import org.thymeleaf.util.Validate;

/**
 *
 * @author Daniel Fern&aacute;ndez
 * @since 3.0.0
 *
 */
public final class ProcessorTemplateHandler extends AbstractTemplateHandler {

    private static final Logger logger = LoggerFactory.getLogger(ProcessorTemplateHandler.class);

    private static final String DEFAULT_STATUS_VAR_SUFFIX = "Stat";

    private final ElementTagActionHandler actionHandler;

    private ITemplateProcessingContext templateProcessingContext;
    private ITemplateEngineConfiguration configuration;
    private TemplateMode templateMode;

    private ILocalVariableAwareVariablesMap variablesMap;

    private int markupLevel = 0;

    private int skipMarkupFromLevel = Integer.MAX_VALUE;
    private LevelArray skipCloseTagLevels = new LevelArray(5);

    private final ProcessorIterator processorIterator = new ProcessorIterator();

    // This should only be modified by means of the 'increaseHandlerExecLevel' and 'decreaseHandlerExecLevel' methods
    private int handlerExecLevel = -1;

    // These structures will be indexed by the handlerExecLevel, which allows structures to be used across different levels of nesting
    private EngineEventQueue[] eventQueues = null;
    private Text[] textBuffers = null;

    // Used for suspending the execution of a tag and replacing it for a different event (perhaps after building a
    // queue) or iterating the suspended event and its body.
    private boolean suspended = false;
    private SuspensionSpec suspensionSpec; // Will be initialized once we have the processing context
    private boolean gatheringIteration = false;
    private IterationSpec iterationSpec;



    /**
     * <p>
     *   Creates a new instance of this handler.
     * </p>
     *
     */
    public ProcessorTemplateHandler() {
        super();
        this.actionHandler = new ElementTagActionHandler();
    }




    @Override
    public void setTemplateProcessingContext(final ITemplateProcessingContext templateProcessingContext) {

        super.setTemplateProcessingContext(templateProcessingContext);

        this.templateProcessingContext = templateProcessingContext;
        Validate.notNull(this.templateProcessingContext, "Template Processing Context cannot be null");
        Validate.notNull(this.templateProcessingContext.getTemplateMode(), "Template Mode returned by Template Processing Context cannot be null");

        this.configuration = templateProcessingContext.getConfiguration();
        Validate.notNull(this.configuration, "Template Engine Configuration returned by Template Processing Context cannot be null");
        Validate.notNull(this.configuration.getTextRepository(), "Text Repository returned by Template Engine Configuration cannot be null");
        Validate.notNull(this.configuration.getElementDefinitions(), "Element Definitions returned by Template Engine Configuration cannot be null");
        Validate.notNull(this.configuration.getAttributeDefinitions(), "Attribute Definitions returned by Template Engine Configuration cannot be null");

        this.templateMode = this.templateProcessingContext.getTemplateMode(); // Just a way to avoid doing the call each time

        final IVariablesMap variablesMap = templateProcessingContext.getVariablesMap();
        Validate.notNull(variablesMap, "Variables Map returned by Template Processing Context cannot be null");
        if (variablesMap instanceof ILocalVariableAwareVariablesMap) {
            this.variablesMap = (ILocalVariableAwareVariablesMap) variablesMap;
        } else {
            logger.warn("Unknown implementation of the " + IVariablesMap.class.getName() + " interface: " +
                        variablesMap.getClass().getName() + ". Local variable support will be DISABLED.");
        }

        this.suspensionSpec = new SuspensionSpec(this.templateMode, this.configuration);
        this.iterationSpec = new IterationSpec(this.templateMode, this.configuration);

    }




    private void increaseHandlerExecLevel() {

        this.handlerExecLevel++;

        if (this.eventQueues == null) {
            // No arrays created yet - must create

            this.eventQueues = new EngineEventQueue[3];
            Arrays.fill(this.eventQueues, null);
            this.textBuffers = new Text[3];
            Arrays.fill(this.textBuffers, null);

        }

        if (this.eventQueues.length == this.handlerExecLevel) {
            // We need to grow the arrays

            final EngineEventQueue[] newEventQueues = new EngineEventQueue[this.handlerExecLevel + 3];
            Arrays.fill(newEventQueues, null);
            System.arraycopy(this.eventQueues, 0, newEventQueues, 0, this.handlerExecLevel);
            this.eventQueues = newEventQueues;

            final Text[] newTextBuffers = new Text[this.handlerExecLevel + 3];
            Arrays.fill(newTextBuffers, null);
            System.arraycopy(this.textBuffers, 0, newTextBuffers, 0, this.handlerExecLevel);
            this.textBuffers = newTextBuffers;

        }

        if (this.eventQueues[this.handlerExecLevel] == null) {
            this.eventQueues[this.handlerExecLevel] = new EngineEventQueue(this.templateMode, this.configuration);
        } else {
            this.eventQueues[this.handlerExecLevel].reset();
        }

        if (this.textBuffers[this.handlerExecLevel] == null) {
            // Note we are not using the model factory because we need this exact implementation of the structure interface
            this.textBuffers[this.handlerExecLevel] = new Text(this.configuration.getTextRepository());
        }

    }


    private void decreaseHandlerExecLevel() {
        this.handlerExecLevel--;
    }




    @Override
    public void handleDocumentStart(final long startTimeNanos, final int line, final int col) {
        super.handleDocumentStart(startTimeNanos, line, col);
        increaseHandlerExecLevel();
    }




    @Override
    public void handleDocumentEnd(final long endTimeNanos, final long totalTimeNanos, final int line, final int col) {
        decreaseHandlerExecLevel();
        super.handleDocumentEnd(endTimeNanos, totalTimeNanos, line, col);
    }




    @Override
    public void handleText(final IText itext) {

        // Check whether we just need to discard any markup in this level
        if (this.markupLevel >= this.skipMarkupFromLevel) {
            return;
        }

        // Check whether we are in the middle of an iteration and we just need to cache this to the queue (for now)
        if (this.gatheringIteration && this.markupLevel >= this.iterationSpec.fromMarkupLevel) {
            this.iterationSpec.iterationQueue.add(Text.asEngineText(this.configuration, itext, true));
            return;
        }

        // Includes calling the next handler in the chain
        super.handleText(itext);

    }



    @Override
    public void handleComment(final IComment icomment) {

        // Check whether we just need to discard any markup in this level
        if (this.markupLevel >= this.skipMarkupFromLevel) {
            return;
        }

        // Check whether we are in the middle of an iteration and we just need to cache this to the queue (for now)
        if (this.gatheringIteration && this.markupLevel >= this.iterationSpec.fromMarkupLevel) {
            this.iterationSpec.iterationQueue.add(Comment.asEngineComment(this.configuration, icomment, true));
            return;
        }

        // Includes calling the next handler in the chain
        super.handleComment(icomment);

    }

    
    @Override
    public void handleCDATASection(final ICDATASection icdataSection) {

        // Check whether we just need to discard any markup in this level
        if (this.markupLevel >= this.skipMarkupFromLevel) {
            return;
        }

        // Check whether we are in the middle of an iteration and we just need to cache this to the queue (for now)
        if (this.gatheringIteration && this.markupLevel >= this.iterationSpec.fromMarkupLevel) {
            this.iterationSpec.iterationQueue.add(CDATASection.asEngineCDATASection(this.configuration, icdataSection, true));
            return;
        }

        // Includes calling the next handler in the chain
        super.handleCDATASection(icdataSection);

    }




    @Override
    public void handleStandaloneElement(final IStandaloneElementTag istandaloneElementTag) {

        /*
         * CHECK WHETHER THIS MARKUP REGION SHOULD BE DISCARDED, for example, as a part of a skipped body
         */
        if (this.markupLevel >= this.skipMarkupFromLevel) {
            return;
        }


        /*
         * CHECK WHETHER WE ARE IN THE MIDDLE OF AN ITERATION and we just need to cache this to the queue (for now)
         */
        if (this.gatheringIteration && this.markupLevel >= this.iterationSpec.fromMarkupLevel) {
            this.iterationSpec.iterationQueue.add(
                    StandaloneElementTag.asEngineStandaloneElementTag(
                            this.templateMode, this.configuration, istandaloneElementTag, true));
            return;
        }

/**/ /* NOTE THIS HAS TO BE REMOVED FOR BENCHMARKING AS ITS FORCING UNNECESARY PROCESSOR RECOMPUTATIONS IN CASE EVENTS COME FROM THE CACHE */
        istandaloneElementTag.getAttributes().setAttribute("markupLevel", Integer.valueOf(this.markupLevel).toString());
        istandaloneElementTag.getAttributes().setAttribute("execLevel", Integer.valueOf(this.handlerExecLevel).toString());
        istandaloneElementTag.getAttributes().setAttribute("variablesMapLevel", Integer.valueOf(this.variablesMap.level()).toString());
/**/


        /*
         * FAIL FAST in case this tag has no associated processors and we have no reason to pay attention to it
         * anyway (because of being suspended). This avoids cast to engine-specific implementation for most cases.
         */
        if (!this.suspended && !istandaloneElementTag.hasAssociatedProcessors()) {
            super.handleStandaloneElement(istandaloneElementTag);
            return;
        }


        /*
         * CAST (WITHOUT CLONING) TO ENGINE-SPECIFIC IMPLEMENTATION, which will ease the handling of the structure during processing
         */
        final StandaloneElementTag standaloneElementTag =
                StandaloneElementTag.asEngineStandaloneElementTag(this.templateMode, this.configuration, istandaloneElementTag, false);


        /*
         * DECLARE THE FLAGS NEEDED DURING THE EXECUTION OF PROCESSORS
         */
        boolean tagRemoved = false; // If the tag is removed, we have to immediately stop the execution of processors
        boolean queueProcessable = false; // When elements are added to a queue, we need to know whether it is processable or not


        /*
         * REGISTER A NEW EXEC LEVEL, and allow the corresponding structures to be created just in case they are needed
         */
        increaseHandlerExecLevel();
        EngineEventQueue queue = this.eventQueues[this.handlerExecLevel];


        /*
         * INCREASE THE VARIABLES MAP LEVEL so that all local variables created during the execution of processors
         * are available for the rest of the processors as well as the body of the tag
         */
        if (this.variablesMap != null) {
            this.variablesMap.increaseLevel();
        }


        /*
         * INITIALIZE THE EXECUTION LEVEL depending on whether we have a suspended a previous execution or not
         */
        if (!this.suspended) {

            /*
             * INITIALIZE THE PROCESSOR ITERATOR that will be used for executing all the processors
             */
            this.processorIterator.reset();

        } else {
            // Execution of a tag was suspended, we need to recover the data

            /*
             * RETRIEVE THE QUEUE TO BE USED, potentially already containing some nodes. And also the flags.
             */
            queue.resetAsCloneOf(this.suspensionSpec.suspendedQueue);
            queueProcessable = this.suspensionSpec.queueProcessable;
            this.processorIterator.resetAsCloneOf(this.suspensionSpec.suspendedIterator);
            this.suspended = false;
            this.suspensionSpec.reset();

            // Note we will not increase the VariablesMap level here, as are keeping the level from the suspended execution

        }


        /*
         * EXECUTE PROCESSORS
         */
        IProcessor processor;
        while (!tagRemoved && (processor = this.processorIterator.next(standaloneElementTag)) != null) {

            this.actionHandler.reset();

            if (processor instanceof IElementProcessor) {

                final IElementProcessor elementProcessor = ((IElementProcessor)processor);
                elementProcessor.process(this.templateProcessingContext, standaloneElementTag, this.actionHandler);

                if (this.actionHandler.setLocalVariable) {
                    if (this.variablesMap != null) {
                        this.variablesMap.putAll(this.actionHandler.addedLocalVariables);
                    }
                }

                if (this.actionHandler.removeLocalVariable) {
                    if (this.variablesMap != null) {
                        for (final String variableName : this.actionHandler.removedLocalVariableNames) {
                            this.variablesMap.remove(variableName);
                        }
                    }
                }

                if (this.actionHandler.iterateElement) {

                    this.gatheringIteration = true;
                    this.iterationSpec.fromMarkupLevel = this.markupLevel + 1;
                    this.iterationSpec.iterVariableName = this.actionHandler.iterVariableName;
                    this.iterationSpec.iterStatusVariableName = this.actionHandler.iterStatusVariableName;
                    this.iterationSpec.iteratedObject = this.actionHandler.iteratedObject;
                    this.iterationSpec.iterationQueue.reset();

                    // Suspend the queue - execution will be restarted by the handleOpenElement event
                    this.suspended = true;
                    this.suspensionSpec.bodyRemoved = false;
                    this.suspensionSpec.queueProcessable = queueProcessable;
                    this.suspensionSpec.suspendedQueue.resetAsCloneOf(queue);
                    this.suspensionSpec.suspendedIterator.resetAsCloneOf(this.processorIterator);

                    // Add this standalone tag to the iteration queue
                    this.iterationSpec.iterationQueue.add(standaloneElementTag.cloneElementTag());

                    // Decrease the handler execution level (all important bits are already in suspensionSpec)
                    decreaseHandlerExecLevel();

                    // Note we DO NOT DECREASE THE VARIABLES MAP LEVEL -- we need the variables stored there, if any

                    // Process the queue by iterating it
                    processIteration();

                    // Decrease the variables map level
                    if (this.variablesMap != null) {
                        this.variablesMap.decreaseLevel();
                    }

                    return;

                } else if (this.actionHandler.setBodyText) {

                    queue.reset(); // Remove any previous results on the queue

                    // Prepare the now-equivalent open and close tags
                    final OpenElementTag openTag =
                            new OpenElementTag(this.templateMode,
                                    this.configuration.getElementDefinitions(), this.configuration.getAttributeDefinitions());
                    final CloseElementTag closeTag =
                            new CloseElementTag(this.templateMode, this.configuration.getElementDefinitions());
                    openTag.resetAsCloneOf(standaloneElementTag);
                    closeTag.resetAsCloneOf(standaloneElementTag);

                    // Prepare the text node that will be added to the queue (that we will suspend)
                    this.textBuffers[this.handlerExecLevel].setText(this.actionHandler.setBodyTextValue);
                    queue.add(this.textBuffers[this.handlerExecLevel]);

                    // Suspend the queue - execution will be restarted by the handleOpenElement event
                    this.suspended = true;
                    this.suspensionSpec.bodyRemoved = false;
                    this.suspensionSpec.queueProcessable = this.actionHandler.setBodyTextProcessable;
                    this.suspensionSpec.suspendedQueue.resetAsCloneOf(queue);
                    this.suspensionSpec.suspendedIterator.resetAsCloneOf(this.processorIterator);

                    // Decrease the handler execution level (all important bits are already in suspensionSpec)
                    decreaseHandlerExecLevel();

                    // Note we DO NOT DECREASE THE VARIABLES MAP LEVEL -- we need the variables stored there, if any

                    // Fire the now-equivalent events. Note the handleOpenElement event will take care of the suspended queue
                    handleOpenElement(openTag);
                    handleCloseElement(closeTag);

                    // Decrease the variables map level
                    if (this.variablesMap != null) {
                        this.variablesMap.decreaseLevel();
                    }

                    return;

                } else if (this.actionHandler.setBodyQueue) {

                    queue.reset(); // Remove any previous results on the queue

                    // Prepare the now-equivalent open and close tags
                    final OpenElementTag openTag =
                            new OpenElementTag(this.templateMode,
                                    this.configuration.getElementDefinitions(), this.configuration.getAttributeDefinitions());
                    final CloseElementTag closeTag =
                            new CloseElementTag(this.templateMode, this.configuration.getElementDefinitions());
                    openTag.resetAsCloneOf(standaloneElementTag);
                    closeTag.resetAsCloneOf(standaloneElementTag);

                    // Prepare the queue (that we will suspend)
                    queue.addQueue(this.actionHandler.setBodyQueueValue, true); // we need to clone the queue!

                    // Suspend the queue - execution will be restarted by the handleOpenElement event
                    this.suspended = true;
                    this.suspensionSpec.bodyRemoved = false;
                    this.suspensionSpec.queueProcessable = this.actionHandler.setBodyQueueProcessable;
                    this.suspensionSpec.suspendedQueue.resetAsCloneOf(queue);
                    this.suspensionSpec.suspendedIterator.resetAsCloneOf(this.processorIterator);

                    // Decrease the handler execution level (all important bits are already in suspensionSpec)
                    decreaseHandlerExecLevel();

                    // Note we DO NOT DECREASE THE VARIABLES MAP LEVEL -- we need the variables stored there, if any

                    // Fire the now-equivalent events. Note the handleOpenElement event will take care of the suspended queue
                    handleOpenElement(openTag);
                    handleCloseElement(closeTag);

                    // Decrease the variables map level
                    if (this.variablesMap != null) {
                        this.variablesMap.decreaseLevel();
                    }

                    return;

                } else if (this.actionHandler.replaceWithText) {

                    queue.reset(); // Remove any previous results on the queue
                    queueProcessable = this.actionHandler.replaceWithTextProcessable;

                    this.textBuffers[this.handlerExecLevel].setText(this.actionHandler.setBodyTextValue);

                    queue.add(this.textBuffers[this.handlerExecLevel]);

                    tagRemoved = true;

                } else if (this.actionHandler.replaceWithQueue) {

                    queue.reset(); // Remove any previous results on the queue
                    queueProcessable = this.actionHandler.replaceWithQueueProcessable;

                    queue.addQueue(this.actionHandler.replaceWithQueueValue, true); // we need to clone the queue!

                    tagRemoved = true;

                } else if (this.actionHandler.removeElement) {

                    queue.reset(); // Remove any previous results on the queue

                    tagRemoved = true;

                } else if (this.actionHandler.removeTag) {

                    // No modifications to the queue - it's just the tag that will be removed, not its possible contents

                    tagRemoved = true;

                }

            } else if (processor instanceof INodeProcessor) {
                throw new UnsupportedOperationException("Support for Node processors not implemented yet");
            } else {
                throw new IllegalStateException(
                        "An element has been found with an associated processor of type " + processor.getClass().getName() +
                                " which is neither an element nor a Node processor.");
            }

        }


        /*
         * PROCESS THE REST OF THE HANDLER CHAIN in the case we DID NOT reshape the tag to non-void
         */
        if (!tagRemoved) {
            super.handleStandaloneElement(standaloneElementTag);
        }


        /*
         * PROCESS THE QUEUE, launching all the queued events
         */
        queue.process(queueProcessable ? this : getNext(), true);


        /*
         * DECREASE THE VARIABLES MAP LEVEL once we have executed all the processors (and maybe a body if we added
         * one to the tag converting it into an open tag)
         */
        if (this.variablesMap != null) {
            this.variablesMap.decreaseLevel();
        }


        /*
         * DECREASE THE EXEC LEVEL, so that the structures can be reused
         */
        decreaseHandlerExecLevel();

    }




    @Override
    public void handleOpenElement(final IOpenElementTag iopenElementTag) {

        /*
         * CHECK WHETHER THIS MARKUP REGION SHOULD BE DISCARDED, for example, as a part of a skipped body
         */
        if (this.markupLevel >= this.skipMarkupFromLevel) {
            this.markupLevel++;
            return;
        }


        /*
         * CHECK WHETHER WE ARE IN THE MIDDLE OF AN ITERATION and we just need to cache this to the queue (for now)
         */
        if (this.gatheringIteration && this.markupLevel >= this.iterationSpec.fromMarkupLevel) {
            this.iterationSpec.iterationQueue.add(
                    OpenElementTag.asEngineOpenElementTag(this.templateMode, this.configuration, iopenElementTag, true));
            this.markupLevel++;
            return;
        }

/**/ /* NOTE THIS HAS TO BE REMOVED FOR BENCHMARKING AS ITS FORCING UNNECESARY PROCESSOR RECOMPUTATIONS IN CASE EVENTS COME FROM THE CACHE */
        iopenElementTag.getAttributes().setAttribute("markupLevel", Integer.valueOf(this.markupLevel).toString());
        iopenElementTag.getAttributes().setAttribute("execLevel", Integer.valueOf(this.handlerExecLevel).toString());
        iopenElementTag.getAttributes().setAttribute("variablesMapLevel", Integer.valueOf(this.variablesMap.level()).toString());
/**/

        /*
         * FAIL FAST in case this tag has no associated processors and we have no reason to pay attention to it
         * anyway (because of being suspended). This avoids cast to engine-specific implementation for most cases.
         */
        if (!this.suspended && !iopenElementTag.hasAssociatedProcessors()) {
            super.handleOpenElement(iopenElementTag);
            this.markupLevel++;
            if (this.variablesMap != null) {
                this.variablesMap.increaseLevel();
            }
            return;
        }


        /*
         * CAST (WITHOUT CLONING) TO ENGINE-SPECIFIC IMPLEMENTATION, which will ease the handling of the structure during processing
         */
        final OpenElementTag openElementTag =
                OpenElementTag.asEngineOpenElementTag(this.templateMode, this.configuration, iopenElementTag, false);


        /*
         * DECLARE THE FLAGS NEEDED DURING THE EXECUTION OF PROCESSORS
         */
        boolean tagRemoved = false; // If the tag is removed, we have to immediately stop the execution of processors
        boolean queueProcessable = false; // When elements are added to a queue, we need to know whether it is processable or not
        boolean bodyRemoved = false; // If the body of this tag should be removed, we must signal it accordingly at the engine


        /*
         * REGISTER A NEW EXEC LEVEL, and allow the corresponding structures to be created just in case they are needed
         */
        increaseHandlerExecLevel();
        EngineEventQueue queue = this.eventQueues[this.handlerExecLevel];


        /*
         * INCREASE THE VARIABLES MAP LEVEL so that all local variables created during the execution of processors
         * are available for the rest of the processors as well as the body of the tag
         */
        if (this.variablesMap != null) {
            this.variablesMap.increaseLevel();
        }


        /*
         * INITIALIZE THE EXECUTION LEVEL depending on whether we have a suspended a previous execution or not
         */
        if (!this.suspended) {

            /*
             * INITIALIZE THE PROCESSOR ITERATOR that will be used for executing all the processors
             */
            this.processorIterator.reset();

        } else {
            // Execution of a tag was suspended, we need to recover the data

            /*
             * RETRIEVE THE QUEUE TO BE USED, potentially already containing some nodes. And also the flags.
             */
            queue.resetAsCloneOf(this.suspensionSpec.suspendedQueue);
            queueProcessable = this.suspensionSpec.queueProcessable;
            bodyRemoved = this.suspensionSpec.bodyRemoved;
            this.processorIterator.resetAsCloneOf(this.suspensionSpec.suspendedIterator);
            this.suspended = false;
            this.suspensionSpec.reset();

            // Note we will not increase the VariablesMap level here, as are keeping the level from the suspended execution

        }


        /*
         * EXECUTE PROCESSORS
         */
        IProcessor processor;
        while (!tagRemoved && (processor = this.processorIterator.next(openElementTag)) != null) {

            this.actionHandler.reset();

            if (processor instanceof IElementProcessor) {

                final IElementProcessor elementProcessor = ((IElementProcessor)processor);
                elementProcessor.process(this.templateProcessingContext, openElementTag, this.actionHandler);

                if (this.actionHandler.setLocalVariable) {
                    if (this.variablesMap != null) {
                        this.variablesMap.putAll(this.actionHandler.addedLocalVariables);
                    }
                }

                if (this.actionHandler.removeLocalVariable) {
                    if (this.variablesMap != null) {
                        for (final String variableName : this.actionHandler.removedLocalVariableNames) {
                            this.variablesMap.remove(variableName);
                        }
                    }
                }

                if (this.actionHandler.iterateElement) {

                    this.gatheringIteration = true;
                    this.iterationSpec.fromMarkupLevel = this.markupLevel + 1;
                    this.iterationSpec.iterVariableName = this.actionHandler.iterVariableName;
                    this.iterationSpec.iterStatusVariableName = this.actionHandler.iterStatusVariableName;
                    this.iterationSpec.iteratedObject = this.actionHandler.iteratedObject;
                    this.iterationSpec.iterationQueue.reset();

                    // Suspend the queue - execution will be restarted by the handleOpenElement event
                    this.suspended = true;
                    this.suspensionSpec.bodyRemoved = bodyRemoved;
                    this.suspensionSpec.queueProcessable = queueProcessable;
                    this.suspensionSpec.suspendedQueue.resetAsCloneOf(queue);
                    this.suspensionSpec.suspendedIterator.resetAsCloneOf(this.processorIterator);

                    // The first event in the new iteration query
                    this.iterationSpec.iterationQueue.add(openElementTag.cloneElementTag());

                    // Increase markup level, as normal with open tags
                    this.markupLevel++;

                    // Decrease the handler execution level (all important bits are already in suspensionSpec)
                    decreaseHandlerExecLevel();

                    // Note we DO NOT DECREASE THE VARIABLES MAP LEVEL -- that's the responsibility of the close event

                    // Nothing else to be done by this handler... let's just queue the rest of the events to be iterated
                    return;

                } else if (this.actionHandler.setBodyText) {

                    queue.reset(); // Remove any previous results on the queue
                    queueProcessable = this.actionHandler.setBodyTextProcessable;

                    this.textBuffers[this.handlerExecLevel].setText(this.actionHandler.setBodyTextValue);

                    queue.add(this.textBuffers[this.handlerExecLevel]);

                    bodyRemoved = true;

                } else if (this.actionHandler.setBodyQueue) {

                    queue.reset(); // Remove any previous results on the queue
                    queueProcessable = this.actionHandler.setBodyQueueProcessable;

                    queue.addQueue(this.actionHandler.setBodyQueueValue, true); // we need to clone the queue!

                    bodyRemoved = true;

                } else if (this.actionHandler.replaceWithText) {

                    queue.reset(); // Remove any previous results on the queue
                    queueProcessable = this.actionHandler.replaceWithTextProcessable;

                    this.textBuffers[this.handlerExecLevel].setText(this.actionHandler.setBodyTextValue);

                    queue.add(this.textBuffers[this.handlerExecLevel]);

                    tagRemoved = true;
                    bodyRemoved = true;

                } else if (this.actionHandler.replaceWithQueue) {

                    queue.reset(); // Remove any previous results on the queue
                    queueProcessable = this.actionHandler.replaceWithQueueProcessable;

                    queue.addQueue(this.actionHandler.replaceWithQueueValue, true); // we need to clone the queue!

                    tagRemoved = true;
                    bodyRemoved = true;

                } else if (this.actionHandler.removeElement) {

                    queue.reset(); // Remove any previous results on the queue

                    tagRemoved = true;
                    bodyRemoved = true;

                } else if (this.actionHandler.removeTag) {

                    // No modifications to the queue - it's just the tag that will be removed, not its possible contents

                    tagRemoved = true;

                }

            } else if (processor instanceof INodeProcessor) {
                throw new UnsupportedOperationException("Support for Node processors not implemented yet");
            } else {
                throw new IllegalStateException(
                        "An element has been found with an associated processor of type " + processor.getClass().getName() +
                        " which is neither an element nor a Node processor.");
            }

        }


        /*
         * PROCESS THE REST OF THE HANDLER CHAIN and INCREASE THE MARKUP LEVEL RIGHT AFTERWARDS
         */
        if (!tagRemoved) {
            super.handleOpenElement(openElementTag);
        }


        /*
         * INCREASE THE MARKUP LEVEL to the value that will be applied to the tag's bodies
         */
        this.markupLevel++;


        /*
         * PROCESS THE QUEUE, launching all the queued events
         */
        queue.process(queueProcessable ? this : getNext(), true);


        /*
         * SET BODY TO BE SKIPPED, if required
         */
        if (bodyRemoved) {
            // We make sure no other nested events will be processed at all
            this.skipMarkupFromLevel = this.markupLevel;
        }


        /*
         * MAKE SURE WE SKIP THE CORRESPONDING CLOSE TAG, if required
         */
        if (tagRemoved) {
            this.skipCloseTagLevels.add(this.markupLevel - 1);
            // We cannot decrease here the variables map level because we aren't actually decreasing the markup
            // level until we find the corresponding close tag
        }


        /*
         * DECREASE THE EXEC LEVEL, so that the structures can be reused
         */
        decreaseHandlerExecLevel();

    }




    @Override
    public void handleAutoOpenElement(final IAutoOpenElementTag iautoOpenElementTag) {

        // TODO Once engine code is completed for standalone + open, copy open here

        // Check whether we just need to discard any markup in this level
        if (this.markupLevel >= this.skipMarkupFromLevel) {
            this.markupLevel++;
            if (this.variablesMap != null) {
                this.variablesMap.increaseLevel();
            }
            return;
        }

        // Includes calling the next handler in the chain
        super.handleAutoOpenElement(iautoOpenElementTag);

        // Note we increase the markup level after processing the rest of the chain for this element
        this.markupLevel++;
        if (this.variablesMap != null) {
            this.variablesMap.increaseLevel();
        }

    }




    @Override
    public void handleCloseElement(final ICloseElementTag icloseElementTag) {

        /*
         * DECREASE THE MARKUP LEVEL, as only the body of elements should be considered in a higher level
         */
        this.markupLevel--;

        /*
         * CHECK WHETHER THIS MARKUP REGION SHOULD BE DISCARDED, for example, as a part of a skipped body
         */
        if (this.markupLevel >= this.skipMarkupFromLevel) {
            return;
        }

        /*
         * CHECK WHETHER WE ARE IN THE MIDDLE OF AN ITERATION and we just need to cache this to the queue (for now)
         */
        if (this.gatheringIteration && this.markupLevel >= this.iterationSpec.fromMarkupLevel) {
            this.iterationSpec.iterationQueue.add(
                    CloseElementTag.asEngineCloseElementTag(this.templateMode, this.configuration, icloseElementTag, true));
            return;
        }

        /*
         * CHECK WHETHER WE ARE JUST CLOSING AN ITERATION, and in such case, process it
         */
        if (this.gatheringIteration && this.markupLevel + 1 == this.iterationSpec.fromMarkupLevel) {

            // Add the last tag: the closing one
            this.iterationSpec.iterationQueue.add(
                    CloseElementTag.asEngineCloseElementTag(this.templateMode, this.configuration, icloseElementTag, true));

            // Process the queue by iterating it
            processIteration();

            // Decrease the variables map level
            if (this.variablesMap != null) {
                this.variablesMap.decreaseLevel();
            }

            return;

        }

        /*
         * DECREASE THE VARIABLES MAP LEVEL, once we know this tag was not part of a block of discarded markup
         */
        if (this.variablesMap != null) {
            this.variablesMap.decreaseLevel();
        }

        /*
         * CHECK WHETHER WE SHOULD KEEP SKIPPING MARKUP or we just got to the end of the discarded block
         */
        if (this.markupLevel + 1 == this.skipMarkupFromLevel) {
            // We've reached the last point where markup should be discarded, so we should reset the variable
            this.skipMarkupFromLevel = Integer.MAX_VALUE;
        }

        /*
         * CHECK WHETHER THIS CLOSE TAG ITSELF MUST BE DISCARDED because we also discarded the open one (even if not necessarily the body)
         */
        if (this.skipCloseTagLevels.matchAndPop(this.markupLevel)) {
            return;
        }

        /*
         * CALL THE NEXT HANDLER in the chain
         */
        super.handleCloseElement(icloseElementTag);

    }




    @Override
    public void handleAutoCloseElement(final IAutoCloseElementTag iautoCloseElementTag) {

        /*
         * DECREASE THE MARKUP LEVEL, as only the body of elements should be considered in a higher level
         */
        this.markupLevel--;

        /*
         * CHECK WHETHER THIS MARKUP REGION SHOULD BE DISCARDED, for example, as a part of a skipped body
         */
        if (this.markupLevel >= this.skipMarkupFromLevel) {
            return;
        }

        /*
         * CHECK WHETHER WE ARE IN THE MIDDLE OF AN ITERATION and we just need to cache this to the queue (for now)
         */
        if (this.gatheringIteration && this.markupLevel >= this.iterationSpec.fromMarkupLevel) {
            this.iterationSpec.iterationQueue.add(
                    AutoCloseElementTag.asEngineAutoCloseElementTag(this.templateMode, this.configuration, iautoCloseElementTag, true));
            return;
        }

        /*
         * CHECK WHETHER WE ARE JUST CLOSING AN ITERATION, and in such case, process it
         */
        if (this.gatheringIteration && this.markupLevel + 1 == this.iterationSpec.fromMarkupLevel) {

            // Add the last tag: the closing one
            this.iterationSpec.iterationQueue.add(
                    AutoCloseElementTag.asEngineAutoCloseElementTag(this.templateMode, this.configuration, iautoCloseElementTag, true));

            // Process the queue by iterating it
            processIteration();

            // Decrease the variables map level
            if (this.variablesMap != null) {
                this.variablesMap.decreaseLevel();
            }

            return;

        }

        /*
         * DECREASE THE VARIABLES MAP LEVEL, once we know this tag was not part of a block of discarded markup
         */
        if (this.variablesMap != null) {
            this.variablesMap.decreaseLevel();
        }

        /*
         * CHECK WHETHER WE SHOULD KEEP SKIPPING MARKUP or we just got to the end of the discarded block
         */
        if (this.markupLevel + 1 == this.skipMarkupFromLevel) {
            // We've reached the last point where markup should be discarded, so we should reset the variable
            this.skipMarkupFromLevel = Integer.MAX_VALUE;
        }

        /*
         * CHECK WHETHER THIS CLOSE TAG ITSELF MUST BE DISCARDED because we also discarded the open one (even if not necessarily the body)
         */
        if (this.skipCloseTagLevels.matchAndPop(this.markupLevel)) {
            return;
        }

        /*
         * CALL THE NEXT HANDLER in the chain
         */
        super.handleAutoCloseElement(iautoCloseElementTag);

    }




    @Override
    public void handleUnmatchedCloseElement(final IUnmatchedCloseElementTag iunmatchedCloseElementTag) {

        // Check whether we just need to discard any markup in this level
        if (this.markupLevel >= this.skipMarkupFromLevel) {
            return;
        }

        // Check whether we are in the middle of an iteration and we just need to cache this to the queue (for now)
        if (this.gatheringIteration && this.markupLevel >= this.iterationSpec.fromMarkupLevel) {
            this.iterationSpec.iterationQueue.add(
                    UnmatchedCloseElementTag.asEngineUnmatchedCloseElementTag(this.templateMode, this.configuration, iunmatchedCloseElementTag, true));
            return;
        }

        // Unmatched closes do not affect the markup level


        // Includes calling the next handler in the chain
        super.handleUnmatchedCloseElement(iunmatchedCloseElementTag);

    }




    @Override
    public void handleDocType(final IDocType idocType) {

        // Check whether we just need to discard any markup in this level
        if (this.markupLevel >= this.skipMarkupFromLevel) {
            return;
        }

        // Check whether we are in the middle of an iteration and we just need to cache this to the queue (for now)
        if (this.gatheringIteration && this.markupLevel >= this.iterationSpec.fromMarkupLevel) {
            this.iterationSpec.iterationQueue.add(DocType.asEngineDocType(this.configuration, idocType, true));
            return;
        }

        // Includes calling the next handler in the chain
        super.handleDocType(idocType);

    }

    
    
    
    @Override
    public void handleXmlDeclaration(final IXMLDeclaration ixmlDeclaration) {

        // Check whether we just need to discard any markup in this level
        if (this.markupLevel >= this.skipMarkupFromLevel) {
            return;
        }

        // Check whether we are in the middle of an iteration and we just need to cache this to the queue (for now)
        if (this.gatheringIteration && this.markupLevel >= this.iterationSpec.fromMarkupLevel) {
            this.iterationSpec.iterationQueue.add(
                    XMLDeclaration.asEngineXMLDeclaration(this.configuration, ixmlDeclaration, true));
            return;
        }

        // Includes calling the next handler in the chain
        super.handleXmlDeclaration(ixmlDeclaration);

    }






    @Override
    public void handleProcessingInstruction(final IProcessingInstruction iprocessingInstruction) {

        // Check whether we just need to discard any markup in this level
        if (this.markupLevel >= this.skipMarkupFromLevel) {
            return;
        }

        // Check whether we are in the middle of an iteration and we just need to cache this to the queue (for now)
        if (this.gatheringIteration && this.markupLevel >= this.iterationSpec.fromMarkupLevel) {
            this.iterationSpec.iterationQueue.add(
                    ProcessingInstruction.asEngineProcessingInstruction(this.configuration, iprocessingInstruction, true));
            return;
        }

        // Includes calling the next handler in the chain
        super.handleProcessingInstruction(iprocessingInstruction);

    }






    private void processIteration() {

        if (this.variablesMap == null) {
            throw new TemplateProcessingException(
                    "Iteration is not supported because local variable support is DISABLED. This is due to " +
                    "the use of an unknown implementation of the " + IVariablesMap.class.getName() + " interface. " +
                    "Use " + StandardTemplateProcessingContextFactory.class.getName() + " in order to avoid this.");
        }


        /*
         * FIX THE ITERATION-RELATED VARIABLES
         */

        final String iterVariableName = this.iterationSpec.iterVariableName;
        String iterStatusVariableName = this.iterationSpec.iterStatusVariableName;
        if (StringUtils.isEmptyOrWhitespace(iterStatusVariableName)) {
            // If no name has been specified for the status variable, we will use the same as the iter var + "Stat"
            iterStatusVariableName = iterVariableName + DEFAULT_STATUS_VAR_SUFFIX;
        }
        final Object iteratedObject = this.iterationSpec.iteratedObject;
        final EngineEventQueue iterationQueue = this.iterationSpec.iterationQueue.cloneEventQueue();

        /*
         * Depending on the class of the iterated object, we will iterate it in one way or another. And also we
         * might have a "size" value for the stat variable or not.
         */
        final Iterator<?> iterator = computeIteratedObjectIterator(iteratedObject);

        final IterationStatusVar status = new IterationStatusVar();
        status.index = 0;
        status.size = computeIteratedObjectSize(iteratedObject);

        // We need to reset it or we won't be able to reuse it in nested iterations
        this.iterationSpec.reset();
        this.gatheringIteration = false;


        /*
         * FIX THE SUSPENSION-RELATED VARIABLES
         */

        final boolean suspendedBodyRemoved = this.suspensionSpec.bodyRemoved;
        final boolean suspendedQueueProcessable = this.suspensionSpec.queueProcessable;
        final EngineEventQueue suspendedQueue = this.suspensionSpec.suspendedQueue.cloneEventQueue();
        final ProcessorIterator suspendedProcessorIterator = this.suspensionSpec.suspendedIterator.cloneIterator();

        // We need to reset it or we won't be able to reuse it in nested executions
        this.suspensionSpec.reset();
        this.suspended = false;


        /*
         * PERFORM THE ITERATION
         */

        while (iterator.hasNext()) {

            status.current = iterator.next();

            this.variablesMap.increaseLevel();

            this.variablesMap.put(iterVariableName, status.current);
            this.variablesMap.put(iterStatusVariableName, status);

            // We will initialize the suspension artifacts just as if we had just suspended it
            this.suspensionSpec.bodyRemoved = suspendedBodyRemoved;
            this.suspensionSpec.queueProcessable = suspendedQueueProcessable;
            this.suspensionSpec.suspendedQueue.resetAsCloneOf(suspendedQueue);
            this.suspensionSpec.suspendedIterator.resetAsCloneOf(suspendedProcessorIterator);
            this.suspended = true;

            iterationQueue.process(this, false);

            this.variablesMap.decreaseLevel();

            status.index++;

        }

        // Finally, clean just in case --even if the queued events should have already cleaned this
        this.suspensionSpec.reset();
        this.suspended = false;

    }







    private static Integer computeIteratedObjectSize(final Object iteratedObject) {
        if (iteratedObject == null) {
            return 0;
        }
        if (iteratedObject instanceof Collection<?>) {
            return ((Collection<?>)iteratedObject).size();
        }
        if (iteratedObject instanceof Map<?,?>) {
            return ((Map<?,?>)iteratedObject).size();
        }
        if (iteratedObject.getClass().isArray()) {
            return Integer.valueOf(Array.getLength(iteratedObject));
        }
        if (iteratedObject instanceof Iterable<?>) {
            return null; // Cannot determine before actually iterating
        }
        if (iteratedObject instanceof Iterator<?>) {
            return null; // Cannot determine before actually iterating
        }
        return 1; // In this case, we will iterate the object as a collection of size 1
    }


    private static Iterator<?> computeIteratedObjectIterator(final Object iteratedObject) {
        if (iteratedObject == null) {
            return Collections.EMPTY_LIST.iterator();
        }
        if (iteratedObject instanceof Collection<?>) {
            return ((Collection<?>)iteratedObject).iterator();
        }
        if (iteratedObject instanceof Map<?,?>) {
            return ((Map<?,?>)iteratedObject).entrySet().iterator();
        }
        if (iteratedObject.getClass().isArray()) {
            return new Iterator<Object>() {

                protected final Object array = iteratedObject;
                protected final int length = Array.getLength(this.array);
                private int i = 0;

                public boolean hasNext() {
                    return this.i < this.length;
                }

                public Object next() {
                    return Array.get(this.array, i++);
                }

                public void remove() {
                    throw new UnsupportedOperationException("Cannot remove from an array iterator");
                }

            };
        }
        if (iteratedObject instanceof Iterable<?>) {
            return ((Iterable<?>)iteratedObject).iterator();
        }
        if (iteratedObject instanceof Iterator<?>) {
            return (Iterator<?>)iteratedObject;
        }
        return Collections.singletonList(iteratedObject).iterator();
    }




    private static class LevelArray {

        private int[] array;
        private int size;


        LevelArray(final int initialLen) {
            super();
            this.array = new int[initialLen];
            this.size = 0;
        }


        void add(final int level) {

            if (this.array.length == this.size) {
                // We need to grow the array!
                final int[] newArray = new int[this.array.length + 5];
                System.arraycopy(this.array,0,newArray,0,this.size);
                this.array = newArray;
            }

            this.array[this.size++] = level;

        }

        boolean matchOrHigher(final int level) {
            if (this.size > 0 && this.array[this.size - 1] <= level) {
                return true;
            }
            return false;
        }

        boolean matchAndPop(final int level) {
            if (this.size > 0 && this.array[this.size - 1] == level) {
                this.size--;
                return true;
            }
            return false;
        }

    }



    private static class IterationSpec {

        private int fromMarkupLevel;
        private String iterVariableName;
        private String iterStatusVariableName;
        private Object iteratedObject;
        final EngineEventQueue iterationQueue;

        IterationSpec(final TemplateMode templateMode, final ITemplateEngineConfiguration configuration) {
            super();
            this.iterationQueue = new EngineEventQueue(templateMode, configuration);
            reset();
        }

        void reset() {
            this.fromMarkupLevel = Integer.MAX_VALUE;
            this.iterVariableName = null;
            this.iterStatusVariableName = null;
            this.iteratedObject = null;
            this.iterationQueue.reset();
        }

    }


    private static class SuspensionSpec {

        boolean bodyRemoved;
        boolean queueProcessable;
        final EngineEventQueue suspendedQueue;
        final ProcessorIterator suspendedIterator;

        SuspensionSpec(final TemplateMode templateMode, final ITemplateEngineConfiguration configuration) {
            super();
            this.suspendedQueue = new EngineEventQueue(templateMode, configuration);
            this.suspendedIterator = new ProcessorIterator();
        }

        void reset() {
            this.bodyRemoved = false;
            this.queueProcessable = false;
            this.suspendedQueue.reset();
            this.suspendedIterator.reset();
        }

    }


}