/***********************************************************************
 * This file is part of LaS-VPE Platform.
 *
 * LaS-VPE Platform is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LaS-VPE Platform is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LaS-VPE Platform.  If not, see <http://www.gnu.org/licenses/>.
 ************************************************************************/

package org.cripac.isee.vpe.util;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * The class Singleton manages singletons of any types.
 *
 * @param <T> The INPUT_TYPE of the object.
 * @author Ken Yu, CRIPAC, 2016
 */
public class Singleton<T> implements Serializable {

    private static final long serialVersionUID = -7565726994857167434L;

    /**
     * Lazy-evaluated instance pool of all the classes.
     */
    private static volatile Map<String, Object> instancePool = null;

    /**
     * Factory for creating a new instance if there is not instance in the pool
     * of class T.
     */
    private final Factory<T> objFactory;

    /**
     * Name of class T.
     */
    private final String typeParameterClass;

    /**
     * Create a singleton manager of specified class T, and update the instance
     * by default.
     *
     * @param objFactory Factory to create new instance of class T when instance of it
     *                   does not exist.
     * @throws Exception On failure creating a new instance.
     */
    public Singleton(Factory<T> objFactory) throws Exception {
        this(objFactory, true);
    }

    /**
     * Create a singleton manager of specified class T.
     *
     * @param objFactory       Factory to create new instance of class T when instance of it
     *                         does not exist.
     * @param toUpdateInstance Whether to update the instance in the pool on create of this
     *                         manager.
     * @throws Exception On failure creating a new instance.
     */
    public Singleton(Factory<T> objFactory, boolean toUpdateInstance) throws Exception {
        this.objFactory = objFactory;
        this.typeParameterClass = objFactory.produce().getClass().getName();

        if (toUpdateInstance) {
            checkPool();
            synchronized (Singleton.class) {
                if (!instancePool.containsKey(typeParameterClass)) {
                    instancePool.put(typeParameterClass, objFactory.produce());
                }
            }
        }
    }

    /**
     * Check existence of the instance pool.
     */
    private void checkPool() {
        if (instancePool == null) {
            synchronized (Singleton.class) {
                if (instancePool == null) {
                    instancePool = new HashMap<>();
                }
            }
        }
    }

    /**
     * Get an instance of class T.
     *
     * @return A singleton instance.
     * @throws Exception On failure creating a new instance.
     */
    public T getInst() throws Exception {
        checkPool();

        if (!instancePool.containsKey(typeParameterClass)) {
            synchronized (Singleton.class) {
                if (!instancePool.containsKey(typeParameterClass)) {
                    instancePool.put(typeParameterClass, objFactory.produce());
                }
            }
        }

        return (T) instancePool.get(typeParameterClass);
    }
}
