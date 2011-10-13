package org.review_board.ereviewboard.ui.editor;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;

import org.apache.commons.io.IOUtils;
import org.eclipse.compare.CompareConfiguration;
import org.eclipse.compare.patch.ApplyPatchOperation;
import org.eclipse.compare.patch.IFilePatch;
import org.eclipse.compare.patch.IFilePatchResult;
import org.eclipse.compare.patch.PatchConfiguration;
import org.eclipse.compare.structuremergeviewer.Differencer;
import org.eclipse.core.resources.IStorage;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.mylyn.internal.reviews.ui.annotations.ReviewCompareAnnotationModel;
import org.eclipse.mylyn.internal.reviews.ui.operations.ReviewCompareEditorInput;
import org.eclipse.mylyn.reviews.core.model.IFileItem;
import org.eclipse.mylyn.reviews.core.model.IFileRevision;
import org.eclipse.mylyn.tasks.core.data.TaskData;
import org.eclipse.mylyn.tasks.ui.TasksUi;
import org.review_board.ereviewboard.core.ReviewboardCorePlugin;
import org.review_board.ereviewboard.core.ReviewboardDiffMapper;
import org.review_board.ereviewboard.core.ReviewboardRepositoryConnector;
import org.review_board.ereviewboard.core.client.ReviewboardClient;
import org.review_board.ereviewboard.core.exception.ReviewboardException;
import org.review_board.ereviewboard.ui.editor.ext.SCMFileContentsLocator;

/**
 * @author Robert Munteanu
 *
 */
@SuppressWarnings("restriction")
class ReviewboardCompareEditorInput extends ReviewCompareEditorInput {
    
    private final ReviewboardDiffMapper _diffMapper;
    private final TaskData _taskData;
    private final SCMFileContentsLocator _locator;

    /**
     * @param file
     * @param diffMapper
     * @param taskData
     * @param codeRepository
     * @param locator the locator, already initialised for the specified <tt>file</tt> )
     */
    ReviewboardCompareEditorInput(IFileItem file, ReviewboardDiffMapper diffMapper, TaskData taskData, SCMFileContentsLocator locator) {
        super(file, new ReviewCompareAnnotationModel(file, null), new CompareConfiguration());
        _diffMapper = diffMapper;
        _taskData = taskData;
        _locator = locator;
    }

    @Override
    protected Object prepareInput(IProgressMonitor monitor)
            throws InvocationTargetException, InterruptedException {
        int taskId = Integer.parseInt(_taskData.getTaskId());
        int diffRevision = _diffMapper.getDiffRevision();
        int fileId = Integer.parseInt(getFile().getId());
        
        ReviewboardRepositoryConnector connector = ReviewboardCorePlugin.getDefault().getConnector();
        
        ReviewboardClient client = connector.getClientManager().getClient(TasksUi.getRepositoryManager().getRepository(ReviewboardCorePlugin.REPOSITORY_KIND, _taskData.getRepositoryUrl()));
        
        try {
            monitor.beginTask("Generating diff", 4);
            
            IFilePatch patch = getPatchForFile(monitor, taskId, diffRevision, fileId, client);
            
            IFilePatchResult patchResult = applyPatch(monitor, patch);

            return findDifferences(monitor, patchResult);
            
        } catch (ReviewboardException e) {
            throw new InvocationTargetException(e);
        } catch (CoreException e) {
            throw new InvocationTargetException(e);
        } catch (IOException e) {
            throw new InvocationTargetException(e);
        } finally {
            monitor.done();
        }
    }

    /**
     * Retrieves and parses a diff from the remote reviewboard instance
     * @param monitor
     * @param taskId
     * @param diffRevision
     * @param fileId
     * @param client
     * @return
     * @throws ReviewboardException
     * @throws CoreException
     */
    private IFilePatch getPatchForFile(IProgressMonitor monitor, int taskId, int diffRevision, int fileId, ReviewboardClient client)
            throws ReviewboardException, CoreException {
        
        final byte[] diff = client.getRawFileDiff(taskId, diffRevision, fileId, monitor);
        monitor.worked(1);

        IFilePatch[] parsedPatches = ApplyPatchOperation.parsePatch(new ByteArrayStorage(diff));
        
        if ( parsedPatches.length != 1 )
            throw new ReviewboardException("Parsed " + parsedPatches.length + ", expected 1.");
        
        return parsedPatches[0];
    }

    private IFilePatchResult applyPatch(IProgressMonitor monitor, IFilePatch patch) throws CoreException {
        
        PatchConfiguration patchConfiguration = new PatchConfiguration();
        IStorage source = lookupResource(getFile().getBase(), monitor);
        monitor.worked(1);
        
        IFilePatchResult patchResult = patch.apply(source, patchConfiguration, monitor);
        monitor.worked(1);
        return patchResult;
    }

    private IStorage lookupResource(IFileRevision fileRevision, IProgressMonitor monitor) throws CoreException {
        
        return new ByteArrayStorage(_locator.getContents(monitor));
    }

    private Object findDifferences(IProgressMonitor monitor, IFilePatchResult patchResult) throws IOException {
        
        byte[] baseContent = IOUtils.toByteArray(patchResult.getOriginalContents());
        byte[] targetContent = IOUtils.toByteArray(patchResult.getPatchedContents());
        
        ByteArrayInput baseInput = new ByteArrayInput(baseContent, getFile().getBase().getPath());
        ByteArrayInput targetInput = new ByteArrayInput(targetContent, getFile().getTarget().getPath());
        
        Object differences = new Differencer().findDifferences(false, monitor, null, null, baseInput, targetInput);
        
        monitor.worked(1);
        
        return differences;
    }
}