/**
 *  Copyright (c) 2013-2016 Angelo ZERR.
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License v1.0
 *  which accompanies this distribution, and is available at
 *  http://www.eclipse.org/legal/epl-v10.html
 *
 *  Contributors:
 *  Angelo Zerr <angelo.zerr@gmail.com> - initial API and implementation
 */
package org.eclipse.angularjs.internal.ui.launchConfigurations;

import java.io.File;

import org.eclipse.angularjs.core.AngularCorePreferencesSupport;
import org.eclipse.angularjs.core.launchConfigurations.IProtractorLaunchConfigurationConstants;
import org.eclipse.angularjs.core.launchConfigurations.ProtractorConfigException;
import org.eclipse.angularjs.core.launchConfigurations.ProtractorLaunchHelper;
import org.eclipse.angularjs.internal.ui.AngularUIMessages;
import org.eclipse.angularjs.internal.ui.AngularUIPlugin;
import org.eclipse.angularjs.internal.ui.preferences.protractor.ProtractorPreferencesPage;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.ui.ILaunchShortcut2;
import org.eclipse.jface.dialogs.ErrorDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.dialogs.PreferencesUtil;

import tern.eclipse.ide.server.nodejs.core.debugger.INodejsDebugger;
import tern.eclipse.ide.server.nodejs.core.debugger.VariableHelper;

/**
 * Protractor launch shortcut.
 *
 */
public class ProtractorLaunchShortcut implements ILaunchShortcut2 {

	@Override
	public void launch(ISelection selection, final String mode) {
		if (selection instanceof IStructuredSelection) {
			IStructuredSelection structuredSelection = (IStructuredSelection) selection;
			Object object = structuredSelection.getFirstElement();
			if (object instanceof IAdaptable) {
				final IResource resource = (IResource) ((IAdaptable) object).getAdapter(IResource.class);
				launch(resource, mode);
			}
		}
	}

	@Override
	public void launch(IEditorPart editor, String mode) {
		IEditorInput input = editor.getEditorInput();
		IFile file = (IFile) input.getAdapter(IFile.class);
		launch(file, mode);
	}

	protected void launch(IResource resource, final String mode) {

		if (resource != null && resource.getType() == IResource.FILE) {
			try {
				// protractor config file to start
				IFile protractorConfigFile = (IFile) resource;

				// Get protractor launch type
				ILaunchManager manager = DebugPlugin.getDefault().getLaunchManager();
				ILaunchConfigurationType launchType = manager.getLaunchConfigurationType(
						IProtractorLaunchConfigurationConstants.ID_PROTRACTOR_LAUNCH_CONFIGURATION_TYPE);

				ILaunchConfigurationWorkingCopy workingCopy = null;
				// Try to find existing launch
				ILaunchConfiguration configuration = getExistingLaunchConfiguration(launchType, protractorConfigFile);
				if (configuration != null) {
					// Get working copy
					workingCopy = configuration.getWorkingCopy();
				} else {
					// Create protractor launch working copy.
					// debugger to use
					INodejsDebugger debugger = getDebugger();

					// nodejs install path
					File nodeInstallPath = getNodeInstallPath();

					// protractor/lib/cli.js
					IFile protractorCliFile = getProtractorCliFile(protractorConfigFile);

					String launchName = "Protractor for " + protractorConfigFile.getFullPath().toString();
					workingCopy = launchType.newInstance(null, manager.generateLaunchConfigurationName(launchName));
					workingCopy.setAttribute(IProtractorLaunchConfigurationConstants.ATTR_NODE_INSTALL_PATH,
							nodeInstallPath.toString());
					workingCopy.setAttribute(IProtractorLaunchConfigurationConstants.ATTR_DEBUGGER, debugger.getId());
					workingCopy.setAttribute(IProtractorLaunchConfigurationConstants.ATTR_PROTRACTOR_CLI_FILE,
							VariableHelper.getWorkspaceLoc(protractorCliFile));
				}
				workingCopy.setAttribute(IProtractorLaunchConfigurationConstants.ATTR_PROTRACTOR_CONFIG_FILE,
						VariableHelper.getWorkspaceLoc(protractorConfigFile));

				// launch protractor
				workingCopy.launch(mode, null);
				workingCopy.doSave();
			} catch (ProtractorConfigException e) {
				reportConfigError(resource, mode, e);
				return;
			} catch (CoreException e) {
				Throwable cause = e.getCause();
				if (cause instanceof ProtractorConfigException) {
					reportConfigError(resource, mode, (ProtractorConfigException) cause);
				} else {
					reportError("Error while executing protractor", e);
				}
				return;
			} catch (Exception e) {
				reportError("Error while executing protractor", e);
			}
		}
	}

	private void reportConfigError(IResource resource, final String mode, ProtractorConfigException e) {
		Shell shell = AngularUIPlugin.getActiveWorkbenchWindow().getShell();
		if (MessageDialog.openConfirm(shell, AngularUIMessages.ProtractorLaunchShortcut_Error,
				e.getMessage() + " Do you want to update Protractor preferences?")) {
			if (PreferencesUtil.createPreferenceDialogOn(shell, ProtractorPreferencesPage.PAGE_ID,
					new String[] { ProtractorPreferencesPage.PAGE_ID }, null).open() == Window.OK) {
				launch(resource, mode);
			}
		}
		return;
	}

	private ILaunchConfiguration getExistingLaunchConfiguration(ILaunchConfigurationType type,
			IFile protractorConfigFile) throws CoreException {
		String attr = VariableHelper.getWorkspaceLoc(protractorConfigFile);
		ILaunchManager manager = DebugPlugin.getDefault().getLaunchManager();
		ILaunchConfiguration[] configs = manager.getLaunchConfigurations(type);
		for (ILaunchConfiguration config : configs) {
			if (attr.equals(config.getAttribute(IProtractorLaunchConfigurationConstants.ATTR_PROTRACTOR_CONFIG_FILE,
					(String) null))) {
				return config;
			}
		}
		return null;
	}

	private IFile getProtractorCliFile(IFile protractorConfigFile) throws ProtractorConfigException, CoreException {
		IProject project = protractorConfigFile.getProject();
		IFile cliFile = project.getFile("node_modules/protractor/lib/cli.js");
		if (cliFile != null && cliFile.exists()) {
			return cliFile;
		}
		return AngularCorePreferencesSupport.getInstance().getProtractorCliFile();
	}

	private INodejsDebugger getDebugger() throws ProtractorConfigException, CoreException {
		String debuggerId = AngularCorePreferencesSupport.getInstance().getDebugger();
		return ProtractorLaunchHelper.getDebugger(debuggerId);
	}

	private File getNodeInstallPath() throws ProtractorConfigException, CoreException {
		return AngularCorePreferencesSupport.getInstance().getInstallPath();
	}

	@Override
	public ILaunchConfiguration[] getLaunchConfigurations(ISelection selection) {
		return null;
	}

	@Override
	public ILaunchConfiguration[] getLaunchConfigurations(IEditorPart editor) {
		return null;
	}

	@Override
	public IResource getLaunchableResource(ISelection selection) {
		return null;
	}

	@Override
	public IResource getLaunchableResource(IEditorPart editor) {
		IEditorInput input = editor.getEditorInput();
		return (IResource) input.getAdapter(IFile.class);
	}

	/**
	 * Opens an error dialog presenting the user with the specified message and
	 * throwable
	 * 
	 * @param message
	 * @param throwable
	 */
	protected static void reportError(String message, Throwable throwable) {
		IStatus status = null;
		if (throwable instanceof CoreException) {
			status = ((CoreException) throwable).getStatus();
		} else {
			status = new Status(IStatus.ERROR, AngularUIPlugin.PLUGIN_ID, 0, message, throwable);
		}
		ErrorDialog.openError(AngularUIPlugin.getActiveWorkbenchWindow().getShell(),
				AngularUIMessages.ProtractorLaunchShortcut_Error,
				AngularUIMessages.ProtractorLaunchShortcut_Protractor_Failed, status);
	}
}
