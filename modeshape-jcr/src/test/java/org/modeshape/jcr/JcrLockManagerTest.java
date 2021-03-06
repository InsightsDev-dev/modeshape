/*
 * ModeShape (http://www.modeshape.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.modeshape.jcr;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.concurrent.TimeUnit;
import javax.jcr.InvalidItemStateException;
import javax.jcr.Node;
import javax.jcr.Session;
import javax.jcr.lock.Lock;
import javax.jcr.lock.LockManager;
import org.junit.Assert;
import org.junit.Test;
import org.modeshape.common.FixFor;

/**
 * Unit test for {@link JcrLockManager}
 *
 * @author Horia Chiorean (hchiorea@redhat.com)
 */
public class JcrLockManagerTest extends SingleUseAbstractTest {
    
    @Test
    @FixFor( "MODE-2047" )
    public void shouldNotAllowLockOnTransientNode() throws Exception {
        AbstractJcrNode testNode = session.getRootNode().addNode("test");
        testNode.addMixin("mix:lockable");
        JcrLockManager lockManager = session.lockManager();
        try {
            lockManager.lock(testNode, true, false, Long.MAX_VALUE, null);
            fail("Transient nodes should not be locked");
        } catch (InvalidItemStateException e) {
            // expected
        }
    }
    
    @Test
    @FixFor( "MODE-2342" )
    public void lockTokensShouldBeRemovedFromSessionUponLogout() throws Exception {
        final AbstractJcrNode testNode = session.getRootNode().addNode("test");
        final String path = testNode.getPath();
        testNode.addMixin("mix:lockable");
        session.save();
        final Lock lock = session.getWorkspace().getLockManager().lock(path,
                                                                       false, false, Long.MAX_VALUE, session.getUserID());
        final String token = lock.getLockToken();
        Assert.assertNotNull(token);
        session.logout();
        
        Session session2 = repository.login();
        final LockManager lockManager = session2.getWorkspace().getLockManager();
        lockManager.addLockToken(token);
        Assert.assertTrue("New session should now own the lock.", lockManager.getLock(path).isLockOwningSession());
    }
    
    @Test
    @FixFor( "MODE-2424" )
    public void shouldAllowAddingMixinOnLockedNodeForLockOwner() throws Exception {
        final AbstractJcrNode testNode = session.getRootNode().addNode("test");
        final String path = testNode.getPath();
        testNode.addMixin("mix:lockable");
        session.save();
        session.getWorkspace().getLockManager().lock(path, false, true, Long.MAX_VALUE, session.getUserID());
        
        testNode.addMixin("mix:created");
        session.save();
    }
    
    @Test
    @FixFor( "MODE-2450" )
    public void shouldCleanupCorruptedLocks() throws Exception {
        final AbstractJcrNode testNode = session.getRootNode().addNode("test");
        final String path = testNode.getPath();
        testNode.addMixin("mix:lockable");
        session.save();
        final org.modeshape.jcr.RepositoryLockManager.Lock lock = (RepositoryLockManager.Lock)
                session.getWorkspace().getLockManager().lock(path, false, false, Long.MAX_VALUE, session.getUserID());
        Assert.assertNotNull(lock);
        session.logout();
        
        //forcibly remove the lock node from the system area...
        String lockKey = lock.lockKey().toString();
        assertTrue(runInTransaction(() -> repository.documentStore().remove(lockKey)));
        
        //and then force a refresh
        RepositoryLockManager lockManager = repository.lockManager();
        lockManager.refreshFromSystem();
        
        //check that the lock has been removed 
        session = repository.login();
        assertFalse(session.getWorkspace().getLockManager().isLocked("/test"));
        
        // issue another refresh and verify the node is still unlocked
        lockManager.refreshFromSystem();
        assertFalse(session.getWorkspace().getLockManager().isLocked("/test"));
    }
    
    @Test
    @FixFor( "MODE-2633" )
    public void shouldExpireOpenScopedLocks() throws Exception {
        // Create a new lockable node
        Node node = session.getRootNode().addNode("test");
        node.addMixin("mix:lockable");
        session.save();
        
        // Lock the node
        JcrLockManager lockManager = session.getWorkspace().getLockManager();
        lockManager.lock(node.getPath(), false, false, 1, null);
        assertTrue(node.isLocked());
        
        // Wait enough time for the lock to be expired
        Thread.sleep(TimeUnit.SECONDS.toMillis(2));
        
        // The old lock should still be there even though it's expired 
        assertFalse(node.isLocked());
        assertFalse(lockManager.getLock(node.getPath()).isLive());
        
        // Check that a new lock can be obtained
        lockManager.lock(node.getPath(), false, false, 10, null);
        assertTrue(node.isLocked());
    }
}
