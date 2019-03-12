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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.enterprise.context.ApplicationScoped;
import javax.ws.rs.core.MultivaluedMap;
import org.jboss.resteasy.plugins.providers.multipart.InputPart;
import org.jboss.resteasy.plugins.providers.multipart.MultipartFormDataInput;

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

        try {
            List<Path> paths = listSourceFiles(Paths.get("/home/"));
            System.out.println("Lista de ficheros:" + paths);
        } catch (IOException ex) {
            java.util.logging.Logger.getLogger(VehicleRoutingSolverManager.class.getName()).log(Level.SEVERE, null, ex);
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
            customers.add(customer);
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

    public void uploadSolution(MultipartFormDataInput multipartFormDataInput){
        MultivaluedMap<String, String> multivaluedMap = null;
        String fileName = null;
        InputStream inputStream = null;
        String uploadFilePath = null;
        try {
            Map<String, List<InputPart>> map = multipartFormDataInput.getFormDataMap();
            List<InputPart> lstInputPart = map.get("fileToUpload");
 
            for(InputPart inputPart : lstInputPart){
 
                // get filename to be uploaded
                multivaluedMap = inputPart.getHeaders();
                fileName = getFileName(multivaluedMap);
 
                if(null != fileName && !"".equalsIgnoreCase(fileName)){
 
                    // write & upload file to UPLOAD_FILE_SERVER
                    inputStream = inputPart.getBody(InputStream.class,null);
                    uploadFilePath = writeToFileServer(inputStream, fileName);
                    System.out.println("AÑADIDO ARCHIVO EN " + uploadFilePath);
                    // close the stream
                    inputStream.close();
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    /**
     *
     * @param inputStream
     * @param fileName
     * @throws IOException
     */
    private String writeToFileServer(InputStream inputStream, String fileName) throws IOException {
        OutputStream outputStream = null;
        final String directory = "/home/";
        final String qualifiedUploadFilePath = directory + fileName;
 
        try {
            outputStream = new FileOutputStream(new File(qualifiedUploadFilePath));
            int read = 0;
            byte[] bytes = new byte[1024];
            while ((read = inputStream.read(bytes)) != -1) {
                outputStream.write(bytes, 0, read);
            }
            outputStream.flush();
        }
        catch (IOException ioe) {
            ioe.printStackTrace();
        }
        finally{
            //release resource, if any
            outputStream.close();
        }
        return qualifiedUploadFilePath;
    }
 
    /**
     *
     * @param multivaluedMap
     * @return file name in String format
     */
    private String getFileName(MultivaluedMap<String, String> multivaluedMap) {
        String[] contentDisposition = multivaluedMap.getFirst("Content-Disposition").split(";");
 
        for (String filename : contentDisposition) {
 
            if ((filename.trim().startsWith("filename"))) {
                String[] name = filename.split("=");
                String exactFileName = name[1].trim().replaceAll("\"", "");
                return exactFileName;
            }
        }
        return "UnknownFile";
    }
    
    List<Path> listSourceFiles(Path dir) throws IOException {
       List<Path> result = new ArrayList<>();
       try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.{vrp}")) {
           for (Path entry: stream) {
               result.add(entry);
           }
       } catch (DirectoryIteratorException ex) {
           // I/O error encounted during the iteration, the cause is an IOException
           throw ex.getCause();
       }
       return result;
   }
}
