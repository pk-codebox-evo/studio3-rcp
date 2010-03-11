package com.aptana.radrails.debug.ui.launcher;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.Status;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.ui.DebugUITools;
import org.eclipse.debug.ui.ILaunchShortcut;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IFileEditorInput;
import org.rubypeople.rdt.debug.core.launching.IRubyLaunchConfigurationConstants;

import com.aptana.radrails.debug.ui.DebugUIPlugin;

public class RubyApplicationShortcut implements ILaunchShortcut
{

	@Override
	public void launch(ISelection selection, String mode)
	{
		if (selection instanceof IStructuredSelection)
		{
			try
			{
				IStructuredSelection structured = (IStructuredSelection) selection;
				Object first = structured.getFirstElement();
				if (first instanceof IFile)
				{
					doLaunch((IFile) first, mode);
				}
				else if (first instanceof IAdaptable)
				{
					IAdaptable adapt = (IAdaptable) first;
					first = adapt.getAdapter(IResource.class);
					if (first instanceof IFile)
					{
						doLaunch((IFile) first, mode);
					}
				}
			}
			catch (CoreException e)
			{
				DebugUIPlugin.logError(e);
			}
		}
	}

	protected void doLaunch(IFile rubyElement, String mode) throws CoreException
	{
		ILaunchConfiguration config = findOrCreateLaunchConfiguration(rubyElement, mode);
		if (config != null)
		{
			DebugUITools.launch(config, mode);
		}
	}

	public void launch(IEditorPart editor, String mode)
	{
		IEditorInput input = editor.getEditorInput();
		if (input == null)
		{
			DebugUIPlugin.logError("Could not retrieve input from editor: " + editor.getTitle(), null);
			return;
		}
		if (input instanceof IFileEditorInput)
		{
			IFileEditorInput fileInput = (IFileEditorInput) input;
			IFile file = fileInput.getFile();
			try
			{
				doLaunch(file, mode);
			}
			catch (CoreException e)
			{
				DebugUIPlugin.logError(e);
			}
		}
		// TODO Log error that we need an IFile...
	}

	protected ILaunchConfiguration findOrCreateLaunchConfiguration(IFile rubyFile, String mode) throws CoreException
	{
		ILaunchConfigurationType configType = getRubyLaunchConfigType();

		ILaunchConfiguration[] configs = getLaunchManager().getLaunchConfigurations(configType);
		List<ILaunchConfiguration> candidateConfigs = new ArrayList<ILaunchConfiguration>(configs.length);
		for (ILaunchConfiguration config : configs)
		{
			boolean projectRelativeFileNamesEqual = config.getAttribute(
					IRubyLaunchConfigurationConstants.ATTR_FILE_NAME, "").equals(rubyFile.getLocation().toOSString());
			if (projectRelativeFileNamesEqual)
			{
				candidateConfigs.add(config);
			}
		}

		switch (candidateConfigs.size())
		{
			case 0:
				return createConfiguration(rubyFile);
			case 1:
				return candidateConfigs.get(0);
			default:
				Status status = new Status(Status.WARNING, DebugUIPlugin.getPluginId(), 0,
						"Multiple configurations match", null);
				throw new CoreException(status);
		}
	}

	protected ILaunchConfiguration createConfiguration(IFile rubyFile)
	{
		ILaunchConfiguration config = null;
		try
		{
			ILaunchConfigurationType configType = getRubyLaunchConfigType();
			ILaunchConfigurationWorkingCopy wc = configType.newInstance(null, getLaunchManager()
					.generateUniqueLaunchConfigurationNameFrom(rubyFile.getName()));
			// wc.setAttribute(IRubyLaunchConfigurationConstants.ATTR_PROJECT_NAME, rubyFile.getProject().getName());
			wc.setAttribute(IRubyLaunchConfigurationConstants.ATTR_FILE_NAME, rubyFile.getLocation().toOSString());
			// wc.setAttribute(IRubyLaunchConfigurationConstants.ATTR_WORKING_DIRECTORY, RubyApplicationShortcut
			// .getDefaultWorkingDirectory(rubyFile.getProject()));
			wc.setAttribute(ILaunchConfiguration.ATTR_SOURCE_LOCATOR_ID,
					IRubyLaunchConfigurationConstants.ID_RUBY_SOURCE_LOCATOR);
			config = wc.doSave();
		}
		catch (CoreException ce)
		{
			DebugUIPlugin.logError(ce);
		}
		return config;
	}

	protected ILaunchConfigurationType getRubyLaunchConfigType()
	{
		return getLaunchManager().getLaunchConfigurationType(IRubyLaunchConfigurationConstants.ID_RUBY_APPLICATION);
	}

	protected ILaunchManager getLaunchManager()
	{
		return DebugPlugin.getDefault().getLaunchManager();
	}

	protected static String getDefaultWorkingDirectory(IProject project)
	{
		if (project != null && project.exists())
		{
			return project.getLocation().toOSString();
		}
		// might have been deleted
		return ResourcesPlugin.getWorkspace().getRoot().getLocation().toOSString();
	}

}
