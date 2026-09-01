import java.util.*;
import java.util.concurrent.*;

/* ===================== Exceptions ===================== */
class InvalidPatientIDException extends RuntimeException {
    public InvalidPatientIDException(String id) { super("Invalid patient ID: " + id); }
}
class DuplicateAppointmentException extends RuntimeException {
    public DuplicateAppointmentException(String id) { super("Duplicate appointment ID: " + id); }
}
class OutOfStockException extends RuntimeException {
    public OutOfStockException(String medID, int requested, int available) {
        super("Out of stock for " + medID + ": requested " + requested + ", available " + available);
    }
}

/* ===================== Entities ===================== */
abstract class Patient {
    protected String patientID, name;
    protected int age;
    protected static final double BASE_FEE = 500.0;
    public Patient(String id, String name, int age) { this.patientID = id; this.name = name; this.age = age; }
    public abstract double calculateFee();
    public String getId() { return patientID; }
    public String getName() { return name; }
}
class GeneralPatient extends Patient {
    public GeneralPatient(String id, String name, int age) { super(id, name, age); }
    public double calculateFee() { return BASE_FEE; }
}
class SeniorPatient extends Patient {
    public SeniorPatient(String id, String name, int age) { super(id, name, age); }
    public double calculateFee() { return BASE_FEE * 0.75; } // 25% waiver
}
class EmergencyPatient extends Patient {
    public EmergencyPatient(String id, String name, int age) { super(id, name, age); }
    public double calculateFee() { return 0.0; } // fee waived
}

class Doctor {
    String doctorID, name, specialisation;
    int maxSlots;
    List<String> bookedSlots = new ArrayList<>();
    Queue<Patient> waitlist = new LinkedList<>();
    public Doctor(String id, String name, String spec, int maxSlots) {
        this.doctorID = id; this.name = name; this.specialisation = spec; this.maxSlots = maxSlots;
    }
}

abstract class Notification {
    String recipient, message;
    public Notification(String recipient, String message) { this.recipient = recipient; this.message = message; }
    public abstract void sendAlert();
}
class AppointmentNotification extends Notification {
    public AppointmentNotification(String recipient, String message) { super(recipient, message); }
    public void sendAlert() { System.out.println("[REMINDER] -> " + recipient + " : " + message); }
}
class StockAlertNotification extends Notification {
    public StockAlertNotification(String recipient, String message) { super(recipient, message); }
    public void sendAlert() { System.out.println("[STOCK-ALERT] -> " + recipient + " : " + message); }
}

/* ===================== Services ===================== */
class AppointmentService {
    Map<String, Patient> patientMap = new HashMap<>();
    Map<String, Doctor> doctorMap = new HashMap<>();
    Set<String> appointmentSet = new HashSet<>();
    LinkedList<Notification> notificationQueue = new LinkedList<>();
    final Object lock = new Object();
    int apptCounter = 1;

    public void registerPatient(Patient p) { patientMap.put(p.getId(), p); }
    public void registerDoctor(Doctor d)   { doctorMap.put(d.doctorID, d); }

    public synchronized String bookAppointment(String patientID, String doctorID) {
        Patient p = patientMap.get(patientID);
        if (p == null) throw new InvalidPatientIDException(patientID);
        Doctor d = doctorMap.get(doctorID);
        if (d == null) throw new InvalidPatientIDException(doctorID);

        String apptID = "A" + String.format("%03d", apptCounter++);
        if (appointmentSet.contains(apptID)) throw new DuplicateAppointmentException(apptID);

        synchronized (lock) {
            if (d.bookedSlots.size() < d.maxSlots) {
                d.bookedSlots.add(apptID);
                appointmentSet.add(apptID);
                notificationQueue.add(new AppointmentNotification(p.getName(),
                        "Your appointment " + apptID + " with Dr. " + d.name + " is confirmed."));
                lock.notifyAll();
                System.out.println("[BOOK] " + apptID + " : " + p.getName() + " -> Dr. " + d.name
                        + " (fee = Rs." + p.calculateFee() + ")");
                return apptID;
            } else {
                d.waitlist.add(p);
                System.out.println("[WAITLIST] Dr. " + d.name + " full — " + p.getName() + " added to waitlist");
                return null;
            }
        }
    }

    public synchronized void cancelAppointment(String apptID, String doctorID) {
        Doctor d = doctorMap.get(doctorID);
        if (d != null && d.bookedSlots.remove(apptID)) {
            appointmentSet.remove(apptID);
            System.out.println("[CANCEL] " + apptID + " cancelled for Dr. " + d.name);
            if (!d.waitlist.isEmpty()) {
                Patient promoted = d.waitlist.poll();
                d.bookedSlots.add("A" + String.format("%03d", apptCounter++));
                System.out.println("[PROMOTE] " + promoted.getName() + " moved from waitlist into the freed slot");
            }
        }
    }
}

class PharmacyService {
    Map<String, Integer> reorderLevel = new HashMap<>();
    Hashtable<String, Integer> inventory = new Hashtable<>(); // thread-safe, synchronised methods
    AppointmentService alertService;

    public PharmacyService(AppointmentService alertService) { this.alertService = alertService; }

    public void addMedicine(String id, int qty, int reorder) {
        inventory.put(id, qty);
        reorderLevel.put(id, reorder);
    }

    public void dispenseMedicine(String medID, int qty) {
        Integer stock = inventory.get(medID);
        if (stock == null) throw new InvalidPatientIDException(medID);
        if (stock < qty) throw new OutOfStockException(medID, qty, stock);
        inventory.put(medID, stock - qty);
        System.out.println("[DISPENSE] " + qty + " units of " + medID + " dispensed. Remaining: " + inventory.get(medID));
        if (inventory.get(medID) <= reorderLevel.get(medID)) {
            synchronized (alertService.lock) {
                alertService.notificationQueue.add(new StockAlertNotification("Pharmacy-Admin",
                        medID + " stock low (" + inventory.get(medID) + " units) — reorder required."));
                alertService.lock.notifyAll();
            }
        }
    }

    public void printReport() {
        System.out.println("---- Inventory Utilisation Report ----");
        for (String id : inventory.keySet()) {
            System.out.println(id + " : stock=" + inventory.get(id) + " reorderLevel=" + reorderLevel.get(id));
        }
    }
}

/* ===================== Threads ===================== */
class NotificationDispatchThread extends Thread {
    AppointmentService service;
    volatile boolean running = true;
    public NotificationDispatchThread(AppointmentService service) { this.service = service; super.setPriority(Thread.NORM_PRIORITY - 1); }
    public void run() {
        while (running) {
            Notification n = null;
            synchronized (service.lock) {
                while (service.notificationQueue.isEmpty() && running) {
                    try { service.lock.wait(800); } catch (InterruptedException e) { return; }
                    if (!running) return;
                }
                if (!service.notificationQueue.isEmpty()) n = service.notificationQueue.poll();
            }
            if (n != null) n.sendAlert();
        }
    }
    public void shutdown() { running = false; synchronized (service.lock) { service.lock.notifyAll(); } }
}

/* ===================== Demonstration Driver ===================== */
public class HospitalSystem {
    public static void main(String[] args) throws InterruptedException {
        System.out.println("==================================================================");
        System.out.println(" SMART HOSPITAL PATIENT APPOINTMENT, PHARMACY INVENTORY");
        System.out.println(" AND ALERT NOTIFICATION SYSTEM");
        System.out.println("==================================================================");
        System.out.println("1. Register Patient   2. Register Doctor    3. Book Appointment");
        System.out.println("4. Cancel Appointment 5. Dispense Medicine  6. Inventory Report");
        System.out.println("7. Exit");
        System.out.println("==================================================================\n");

        AppointmentService apptService = new AppointmentService();
        PharmacyService pharmacy = new PharmacyService(apptService);
        NotificationDispatchThread notifThread = new NotificationDispatchThread(apptService);
        notifThread.start();

        // ---- Registration ----
        apptService.registerPatient(new GeneralPatient("P001", "Arun Kumar", 34));
        apptService.registerPatient(new SeniorPatient("P002", "Lakshmi Iyer", 68));
        apptService.registerPatient(new EmergencyPatient("P003", "Ravi Shankar", 45));
        apptService.registerPatient(new GeneralPatient("P004", "Divya Menon", 29));
        apptService.registerDoctor(new Doctor("D001", "Meera Nair", "Cardiology", 2));
        pharmacy.addMedicine("M001", 10, 5);
        System.out.println(">> Registered 4 patients, 1 doctor, 1 medicine (M001, stock=10, reorder<=5)\n");

        // ---- Booking thread ----
        Runnable bookingWork = () -> {
            apptService.bookAppointment("P001", "D001");
            apptService.bookAppointment("P002", "D001");
            apptService.bookAppointment("P003", "D001"); // doctor full -> waitlisted
        };
        Thread bookingThread = new Thread(bookingWork);
        bookingThread.setPriority(Thread.NORM_PRIORITY + 1);
        bookingThread.start();
        bookingThread.join();
        Thread.sleep(300);

        // ---- Cancellation -> waitlist promotion ----
        apptService.cancelAppointment("A001", "D001");
        Thread.sleep(300);

        // ---- Exception: invalid patient ID ----
        try {
            apptService.bookAppointment("P999", "D001");
        } catch (InvalidPatientIDException e) {
            System.out.println("[CAUGHT] " + e.getMessage());
        }

        // ---- Exception: duplicate booking / invalid doctor demonstration ----
        try {
            apptService.bookAppointment("P004", "D999");
        } catch (InvalidPatientIDException e) {
            System.out.println("[CAUGHT] " + e.getMessage());
        }

        // ---- Pharmacy dispensing ----
        pharmacy.dispenseMedicine("M001", 4);
        Thread.sleep(300);
        try {
            pharmacy.dispenseMedicine("M001", 20); // triggers OutOfStockException
        } catch (OutOfStockException e) {
            System.out.println("[CAUGHT] " + e.getMessage());
        }
        pharmacy.dispenseMedicine("M001", 3); // drops below reorder level -> stock alert
        Thread.sleep(600);

        // ---- Report ----
        pharmacy.printReport();

        Thread.sleep(400);
        notifThread.shutdown();
        notifThread.join();
        System.out.println("\n==================================================================");
        System.out.println(" Session complete — all subsystems demonstrated successfully.");
        System.out.println("==================================================================");
    }
}
