package com.aptana.deploy.wizard;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.MessageFormat;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.jface.operation.IRunnableWithProgress;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.wizard.IWizardPage;
import org.eclipse.jface.wizard.Wizard;
import org.eclipse.swt.browser.Browser;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWizard;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.browser.IWorkbenchBrowserSupport;
import org.eclipse.ui.internal.browser.BrowserViewer;
import org.eclipse.ui.internal.browser.WebBrowserEditor;
import org.eclipse.ui.internal.browser.WebBrowserEditorInput;

import com.aptana.deploy.Activator;
import com.aptana.git.core.GitPlugin;
import com.aptana.git.core.model.GitRepository;
import com.aptana.git.core.model.IGitRepositoryManager;
import com.aptana.scripting.model.BundleElement;
import com.aptana.scripting.model.BundleEntry;
import com.aptana.scripting.model.BundleManager;
import com.aptana.scripting.model.CommandElement;
import com.aptana.usage.PingStartup;

public class DeployWizard extends Wizard implements IWorkbenchWizard
{

	private IProject project;

	@Override
	public boolean performFinish()
	{
		IRunnableWithProgress runnable = null;
		// check what the user chose, then do the heavy lifting, or tell the page to finish...
		IWizardPage currentPage = getContainer().getCurrentPage();
		if (currentPage.getName().equals(HerokuDeployWizardPage.NAME))
		{
			HerokuDeployWizardPage page = (HerokuDeployWizardPage) currentPage;
			runnable = createHerokuDeployRunnable(page);
		}
		else if (currentPage.getName().equals(FTPDeployWizardPage.NAME))
		{
			// TODO Set up FTP deployment stuff, run it
		}
		else if (currentPage.getName().equals(HerokuSignupPage.NAME))
		{
			HerokuSignupPage page = (HerokuSignupPage) currentPage;
			runnable = createHerokuSignupRunnable(page);
		}
		// TODO Add branch for capistrano deployment

		if (runnable != null)
		{
			try
			{
				getContainer().run(true, false, runnable);
			}
			catch (Exception e)
			{
				Activator.logError(e);
			}
		}
		return true;
	}

	protected IRunnableWithProgress createHerokuDeployRunnable(HerokuDeployWizardPage page)
	{
		IRunnableWithProgress runnable;
		final String appName = page.getAppName();
		final boolean publishImmediately = page.publishImmediately();
		runnable = new IRunnableWithProgress()
		{

			@Override
			public void run(IProgressMonitor monitor) throws InvocationTargetException, InterruptedException
			{
				SubMonitor sub = SubMonitor.convert(monitor, 100);
				try
				{
					// Initialize git repo for project if necessary
					IGitRepositoryManager manager = GitPlugin.getDefault().getGitRepositoryManager();
					GitRepository repo = manager.createOrAttach(project, sub.newChild(20));
					// TODO What if we didn't create the repo right now, but it is "dirty"?
					// Now do an initial commit
					repo.index().refresh(sub.newChild(15));	
					repo.index().stageFiles(repo.index().changedFiles());
					repo.index().commit("Initial Commit");
					sub.worked(10);
					
					// Run commands to create/deploy
					final String bundleName = "Heroku"; //$NON-NLS-1$
					PlatformUI.getWorkbench().getDisplay().syncExec(new Runnable()
					{

						@Override
						public void run()
						{
							// TODO How do we determine when these commands are done? Probably need to sleep between
							// these...
							CommandElement command = getCommand(bundleName, "Install Gem"); //$NON-NLS-1$
							command.execute();

							// TODO Send along the app name
							command = getCommand(bundleName, "Create App"); //$NON-NLS-1$
							command.execute();

							if (publishImmediately)
							{
								command = getCommand(bundleName, "Deploy App"); //$NON-NLS-1$
								command.execute();
							}
						}
					});

				}
				catch (CoreException ce)
				{
					throw new InvocationTargetException(ce);
				}
				finally
				{
					sub.done();
				}
			}

			private CommandElement getCommand(String bundleName, String commandName)
			{
				BundleEntry entry = BundleManager.getInstance().getBundleEntry(bundleName);
				for (BundleElement bundle : entry.getContributingBundles())
				{
					CommandElement command = bundle.getCommandByName(commandName);
					if (command != null)
						return command;
				}
				return null;
			}
		};
		return runnable;
	}

	protected IRunnableWithProgress createHerokuSignupRunnable(HerokuSignupPage page)
	{
		IRunnableWithProgress runnable;
		final String userID = page.getUserID();
		runnable = new IRunnableWithProgress()
		{

			/**
			 * Send a ping to aptana.com with email address for referral tracking
			 * 
			 * @throws IOException
			 */
			private void sendPing(IProgressMonitor monitor) throws IOException
			{
				HttpURLConnection connection = null;
				try
				{
					StringBuilder builder = new StringBuilder();
					builder.append("http://toolbox.aptana.com/webhook/heroku?request_id="); //$NON-NLS-1$
					builder.append(URLEncoder.encode(PingStartup.getApplicationId(), "UTF-8")); //$NON-NLS-1$
					builder.append("&email="); //$NON-NLS-1$
					builder.append(URLEncoder.encode(userID, "UTF-8")); //$NON-NLS-1$
					builder.append("&type=signup"); //$NON-NLS-1$

					URL url = new URL(builder.toString());
					connection = (HttpURLConnection) url.openConnection();
					connection.setUseCaches(false);
					connection.setAllowUserInteraction(false);
					int responseCode = connection.getResponseCode();
					if (responseCode != HttpURLConnection.HTTP_OK)
					{
						// Log an error
						Activator
								.logError(MessageFormat.format(
										"Received a non-200 response code when sinding ping to: {0}",
										builder.toString()), null);
					}
				}
				finally
				{
					if (connection != null)
						connection.disconnect();
				}
			}

			@Override
			public void run(IProgressMonitor monitor) throws InvocationTargetException, InterruptedException
			{
				SubMonitor sub = SubMonitor.convert(monitor, 100);
				try
				{
					sendPing(sub.newChild(25));
					String javascriptToInject = grabJSToInject(sub.newChild(25));
					openSignup(javascriptToInject, sub.newChild(50));
				}
				catch (Exception e)
				{
					throw new InvocationTargetException(e);
				}
				finally
				{
					sub.done();
				}
			}

			private String grabJSToInject(IProgressMonitor monitor)
			{
				// TODO Grab JS to inject into signup page!
				return "";
			}

			/**
			 * Open the Heroku signup page.
			 * 
			 * @param monitor
			 * @throws Exception
			 */
			@SuppressWarnings("restriction")
			private void openSignup(String javascript, IProgressMonitor monitor) throws Exception
			{
				final String BROWSER_ID = "heroku-signup"; //$NON-NLS-1$
				URL url = new URL("http://api.heroku.com/signup"); //$NON-NLS-1$

				int style = IWorkbenchBrowserSupport.NAVIGATION_BAR | IWorkbenchBrowserSupport.LOCATION_BAR
						| IWorkbenchBrowserSupport.STATUS;
				WebBrowserEditorInput input = new WebBrowserEditorInput(url, style, BROWSER_ID);
				IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
				IEditorPart editorPart = page.openEditor(input, WebBrowserEditor.WEB_BROWSER_EDITOR_ID);
				WebBrowserEditor webBrowserEditor = (WebBrowserEditor) editorPart;
				Field f = WebBrowserEditor.class.getDeclaredField("webBrowser"); //$NON-NLS-1$
				f.setAccessible(true);
				BrowserViewer viewer = (BrowserViewer) f.get(webBrowserEditor);
				Browser browser = viewer.getBrowser();
				browser.execute(javascript);
			}
		};
		return runnable;
	}

	@Override
	public void addPages()
	{
		// Add the first basic page where they choose the deployment option
		addPage(new DeployWizardPage());
		setForcePreviousAndNextButtons(true); // we only add one page here, but we calculate the next page
												// dynamically...
	}

	@Override
	public IWizardPage getNextPage(IWizardPage page)
	{
		// delegate to page because we modify the page list dynamically and don't statically add them via addPage
		return page.getNextPage();
	}

	@Override
	public IWizardPage getPreviousPage(IWizardPage page)
	{
		// delegate to page because we modify the page list dynamically and don't statically add them via addPage
		return page.getPreviousPage();
	}

	@Override
	public void init(IWorkbench workbench, IStructuredSelection selection)
	{
		Object element = selection.getFirstElement();
		if (element instanceof IResource)
		{
			IResource resource = (IResource) element;
			this.project = resource.getProject();
		}
	}

	public IProject getProject()
	{
		return project;
	}

	@Override
	public boolean canFinish()
	{
		IWizardPage page = getContainer().getCurrentPage();
		// We don't want getNextPage() getting invoked so early on first page, because it does auth check on Heroku
		// credentials...
		if (page.getName().equals(DeployWizardPage.NAME))
			return false;
		return page.isPageComplete() && page.getNextPage() == null;
	}

}
