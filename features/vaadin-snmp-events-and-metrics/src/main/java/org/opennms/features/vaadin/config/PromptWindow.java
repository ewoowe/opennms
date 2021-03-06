/*******************************************************************************
 * This file is part of OpenNMS(R).
 *
 * Copyright (C) 2012-2014 The OpenNMS Group, Inc.
 * OpenNMS(R) is Copyright (C) 1999-2014 The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is a registered trademark of The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * OpenNMS(R) is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with OpenNMS(R).  If not, see:
 *      http://www.gnu.org/licenses/
 *
 * For more information contact:
 *     OpenNMS(R) Licensing <license@opennms.org>
 *     http://www.opennms.org/
 *     http://www.opennms.com/
 *******************************************************************************/

package org.opennms.features.vaadin.config;

import com.vaadin.ui.Alignment;
import com.vaadin.ui.Button;
import com.vaadin.v7.ui.HorizontalLayout;
import com.vaadin.v7.ui.TextField;
import com.vaadin.v7.ui.VerticalLayout;
import com.vaadin.ui.Window;

/**
 * The Class Prompt Window.
 * 
 * @author <a href="mailto:agalue@opennms.org">Alejandro Galue</a> 
 */
@SuppressWarnings("serial")
public abstract class PromptWindow extends Window implements Button.ClickListener {

    /** The Event File Name base. */
    private final TextField fileName;

    /** The OK button. */
    private final Button okButton;

    /** The CANCEL button. */
    private final Button cancelButton;

    /**
     * Instantiates a new Event Generator window.
     *
     * @param caption the caption
     * @param fieldLabel the field label
     */
    public PromptWindow(String caption, String fieldLabel) {
        setCaption(caption);
        setModal(true);
        setWidth("400px");
        setHeight("150px");
        setResizable(false);
        setClosable(false);
        addStyleName("dialog");

        fileName = new TextField(fieldLabel);
        fileName.setNullSettingAllowed(false);
        fileName.setWidth("100%");
        fileName.setRequired(true);
        fileName.setRequiredError("This field cannot be null.");

        okButton = new Button("Continue");
        okButton.addClickListener(this);

        cancelButton = new Button("Cancel");
        cancelButton.addClickListener(this);

        HorizontalLayout toolbar = new HorizontalLayout();
        toolbar.addComponent(okButton);
        toolbar.addComponent(cancelButton);

        VerticalLayout layout = new VerticalLayout();
        layout.addComponent(fileName);
        layout.addComponent(toolbar);
        layout.setComponentAlignment(toolbar, Alignment.BOTTOM_RIGHT);

        setContent(layout);
    }

    /* (non-Javadoc)
     * @see com.vaadin.ui.Button.ClickListener#buttonClick(com.vaadin.ui.Button.ClickEvent)
     */
    @Override
    public void buttonClick(Button.ClickEvent event) {
        final Button btn = event.getButton();
        if (btn == okButton) {
            if (fileName.getValue() != null && ! ((String) fileName.getValue()).trim().equals("")) {
                textFieldChanged((String)fileName.getValue());
            }
        }
        close();
    }

    /**
     * Text field changed.
     *
     * @param fieldValue the field value
     */
    public abstract void textFieldChanged(String fieldValue);

}
