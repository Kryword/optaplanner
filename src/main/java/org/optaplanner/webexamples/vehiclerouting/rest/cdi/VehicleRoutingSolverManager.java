/*
 * Copyright 2015 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.optaplanner.webexamples.vehiclerouting.rest.cdi;

import java.io.Serializable;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.enterprise.context.ApplicationScoped;

import org.optaplanner.core.api.solver.Solver;
import org.optaplanner.core.api.solver.SolverFactory;
import org.optaplanner.core.config.solver.termination.TerminationConfig;
import org.optaplanner.examples.vehiclerouting.domain.Customer;
import org.optaplanner.examples.vehiclerouting.domain.Depot;
import org.optaplanner.examples.vehiclerouting.domain.Vehicle;
import org.optaplanner.examples.vehiclerouting.domain.VehicleRoutingSolution;
import org.optaplanner.examples.vehiclerouting.domain.location.AirLocation;
import org.optaplanner.examples.vehiclerouting.domain.location.Location;
import org.optaplanner.examples.vehiclerouting.persistence.VehicleRoutingImporter;
import org.optaplanner.webexamples.vehiclerouting.rest.domain.JsonCustomer;
import org.optaplanner.webexamples.vehiclerouting.rest.domain.JsonVehicleRoute;
import org.optaplanner.webexamples.vehiclerouting.rest.domain.JsonVehicleRoutingSolution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class VehicleRoutingSolverManager implements Serializable {

    protected final transient Logger logger = LoggerFactory.getLogger(this.getClass());

    private static final String SOLVER_CONFIG = "org/optaplanner/examples/vehiclerouting/solver/vehicleRoutingSolverConfig.xml";
    //private static final String IMPORT_DATASET = "/org/optaplanner/webexamples/vehiclerouting/salesianos-n15-k1.vrp";
    private static final String PATH_DATASET = "/home/cristian/optaplanner/prueba-n15-k1.vrp";

    private SolverFactory<VehicleRoutingSolution> solverFactory;
    // TODO After upgrading to JEE 7, replace ExecutorService by ManagedExecutorService:
    // @Resource(name = "DefaultManagedExecutorService")
    // private ManagedExecutorService executor;
    private ExecutorService executor;

    private Map<String, VehicleRoutingSolution> sessionSolutionMap;
    private Map<String, Solver<VehicleRoutingSolution>> sessionSolverMap;

    @PostConstruct
    public synchronized void init() {
        solverFactory = SolverFactory.createFromXmlResource(SOLVER_CONFIG);
        // Always terminate a solver after 2 minutes
        solverFactory.getSolverConfig().setTerminationConfig(new TerminationConfig().withMinutesSpentLimit(2L));
        executor = Executors.newFixedThreadPool(2); // Only 2 because the other examples have their own Executor
        // TODO these probably don't need to be thread-safe because all access is synchronized
        sessionSolutionMap = new ConcurrentHashMap<>();
        sessionSolverMap = new ConcurrentHashMap<>();
    }

    @PreDestroy
    public synchronized void destroy() {
        for (Solver<VehicleRoutingSolution> solver : sessionSolverMap.values()) {
            solver.terminateEarly();
        }
        executor.shutdown();
    }

    public synchronized VehicleRoutingSolution retrieveOrCreateSolution(String sessionId) {
        VehicleRoutingSolution solution = sessionSolutionMap.get(sessionId);
        if (solution == null) {
            URL unsolvedSolutionURL = null;
            try {
                unsolvedSolutionURL = Paths.get(PATH_DATASET).toUri().toURL();
            } catch (MalformedURLException e) {
                throw new IllegalArgumentException("The PATH_DATASET (" + PATH_DATASET + ") is not a valid path.");
            }
            solution = (VehicleRoutingSolution) new VehicleRoutingImporter()
                    .readSolution(unsolvedSolutionURL);
            sessionSolutionMap.put(sessionId, solution);
        }
        return solution;
    }

    public synchronized boolean solve(final String sessionId) {
        final Solver<VehicleRoutingSolution> solver = solverFactory.buildSolver();
        //sessionSolutionMap.clear();
        solver.addEventListener(event -> {
            VehicleRoutingSolution bestSolution = event.getNewBestSolution();
            synchronized (VehicleRoutingSolverManager.this) {
                sessionSolutionMap.put(sessionId, bestSolution);
            }
        });
        if (sessionSolverMap.containsKey(sessionId)) {
            return false;
        }
        sessionSolverMap.put(sessionId, solver);
        final VehicleRoutingSolution solution = retrieveOrCreateSolution(sessionId);
        executor.submit((Runnable) () -> {
            VehicleRoutingSolution bestSolution = solver.solve(solution);
            synchronized (VehicleRoutingSolverManager.this) {
                sessionSolutionMap.put(sessionId, bestSolution);
                sessionSolverMap.remove(sessionId);
            }
        });
        return true;
    }

    public synchronized boolean terminateEarly(String sessionId) {
        sessionSolutionMap.clear();
        Solver<VehicleRoutingSolution> solver = sessionSolverMap.remove(sessionId);
        if (solver != null) {
            solver.terminateEarly();
            return true;
        } else {
            return false;
        }
    }

    public synchronized boolean clearSolution(String sessionId) {
        VehicleRoutingSolution vr = sessionSolutionMap.remove(sessionId);
        Solver<VehicleRoutingSolution> solver = sessionSolverMap.remove(sessionId);
        if (solver != null){
            solver.terminateEarly();
        }

        return (vr != null);
    }
    
    public synchronized boolean updateSolution(String sessionId, JsonVehicleRoutingSolution solucion){
        VehicleRoutingSolution vrs = new VehicleRoutingSolution();
        vrs.setName(solucion.getName());
        // Añado la lista de customers
        List<Customer> customers = new ArrayList<>();
        solucion.getCustomerList().forEach((jsonCustomer) -> {
            Customer customer = new Customer();
            customer.setDemand(jsonCustomer.getDemand());
            Location location = new AirLocation();
            location.setName(jsonCustomer.getLocationName());
            location.setLatitude(jsonCustomer.getLatitude());
            location.setLongitude(jsonCustomer.getLongitude());
            customer.setLocation(location);
        });
        vrs.setCustomerList(customers);
        
        // Añado la lista de vehículos
        List<Vehicle> listaVehiculos = new ArrayList<>();
        for (JsonVehicleRoute jsonVehicleRoute : solucion.getVehicleRouteList()) {
            Depot depot = new Depot();
            depot.setLocation(new AirLocation(0, jsonVehicleRoute.getDepotLatitude(), jsonVehicleRoute.getDepotLongitude()));
            Vehicle vehicle = new Vehicle();
            vehicle.setDepot(depot);
            vehicle.setCapacity(jsonVehicleRoute.getCapacity());
            List<Customer> listaCustomers = new ArrayList<>();
            Customer customerInicial = new Customer();
            if (jsonVehicleRoute.getCustomerList().size() > 0){
                JsonCustomer jsonCustomerInicial = jsonVehicleRoute.getCustomerList().get(0);
                customerInicial.setDemand(jsonCustomerInicial.getDemand());
                customerInicial.setLocation(new AirLocation(0, jsonCustomerInicial.getLatitude(), jsonCustomerInicial.getLongitude()));
                listaCustomers.add(customerInicial);
                for (int i = 1; i < jsonVehicleRoute.getCustomerList().size(); i++) {
                    Customer customer = listaCustomers.get(i-1);
                    JsonCustomer jsonCustomer2 = jsonVehicleRoute.getCustomerList().get(i);
                    Customer customer2 = new Customer();
                    customer2.setLocation(new AirLocation(0, jsonCustomer2.getLatitude(), jsonCustomer2.getLongitude()));
                    customer2.setDemand(jsonCustomer2.getDemand());
                    customer.setNextCustomer(customer2);
                    listaCustomers.add(customer);
                }
                vehicle.setNextCustomer(listaCustomers.get(0));
            }
            listaVehiculos.add(vehicle);
        }
        vrs.setVehicleList(listaVehiculos);
        
        sessionSolutionMap.put(sessionId, vrs);
        
        return true;
    }

}
