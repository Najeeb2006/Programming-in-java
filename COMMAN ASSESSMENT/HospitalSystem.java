import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;

/* =========================================================
   CUSTOM EXCEPTIONS
   ========================================================= */

class InvalidPatientIDException extends RuntimeException {
    public InvalidPatientIDException(String id) { super("Invalid ID: " + id); }
}

class DuplicateAppointmentException extends RuntimeException {
    public DuplicateAppointmentException(String id) { super("Duplicate Appointment ID: " + id); }
}

class OutOfStockException extends RuntimeException {
    public OutOfStockException(String medicineID, int requested, int available) {
        super("Out of Stock for " + medicineID + " | Requested: " + requested + " | Available: " + available);
    }
}


/* =========================================================
   PATIENT CLASSES
   ========================================================= */

abstract class Patient {
    protected String patientID, name;
    protected int age;
    protected static final double BASE_FEE = 500.0;

    public Patient(String patientID, String name, int age) {
        this.patientID = patientID; this.name = name; this.age = age;
    }
    public abstract double calculateFee();
    public String getId() { return patientID; }
    public String getName() { return name; }
    public int getAge() { return age; }
}

class GeneralPatient extends Patient {
    public GeneralPatient(String id, String name, int age) { super(id, name, age); }
    public double calculateFee() { return BASE_FEE; }
}

class SeniorPatient extends Patient {
    public SeniorPatient(String id, String name, int age) { super(id, name, age); }
    public double calculateFee() { return BASE_FEE * 0.75; }
}

class EmergencyPatient extends Patient {
    public EmergencyPatient(String id, String name, int age) { super(id, name, age); }
    public double calculateFee() { return 0; }
}


/* =========================================================
   DOCTOR / MEDICINE / APPOINTMENT
   ========================================================= */

class Doctor {
    String doctorID, name, specialisation;
    int maxSlots;
    List<String> bookedAppointmentIds = new ArrayList<>();

    public Doctor(String doctorID, String name, String specialisation, int maxSlots) {
        this.doctorID = doctorID; this.name = name; this.specialisation = specialisation; this.maxSlots = maxSlots;
    }
}

class Medicine {
    String medicineID, medicineName;
    int quantity, reorderLevel;

    public Medicine(String medicineID, String medicineName, int quantity, int reorderLevel) {
        this.medicineID = medicineID; this.medicineName = medicineName;
        this.quantity = quantity; this.reorderLevel = reorderLevel;
    }
}

class Appointment {
    String appointmentID, patientID, doctorID, status;

    public Appointment(String appointmentID, String patientID, String doctorID, String status) {
        this.appointmentID = appointmentID; this.patientID = patientID;
        this.doctorID = doctorID; this.status = status;
    }
}


/* =========================================================
   NOTIFICATION SYSTEM
   ========================================================= */

abstract class Notification {
    String recipient, message;
    public Notification(String recipient, String message) { this.recipient = recipient; this.message = message; }
    public abstract String formatAlert();
}

class AppointmentNotification extends Notification {
    public AppointmentNotification(String recipient, String message) { super(recipient, message); }
    public String formatAlert() { return "[APPOINTMENT ALERT] -> " + recipient + " : " + message; }
}

class StockAlertNotification extends Notification {
    public StockAlertNotification(String recipient, String message) { super(recipient, message); }
    public String formatAlert() { return "[STOCK ALERT] -> " + recipient + " : " + message; }
}


/* =========================================================
   NOTIFICATION THREAD (dispatches into the GUI log, thread-safely)
   ========================================================= */

class NotificationDispatchThread extends Thread {
    Queue<Notification> notificationQueue;
    volatile boolean running = true;
    HospitalGUI gui;

    public NotificationDispatchThread(Queue<Notification> notificationQueue, HospitalGUI gui) {
        this.notificationQueue = notificationQueue;
        this.gui = gui;
        setDaemon(true);
    }

    public void run() {
        while (running) {
            synchronized (notificationQueue) {
                if (!notificationQueue.isEmpty()) {
                    Notification n = notificationQueue.poll();
                    // GUI updates must happen on the Swing Event Dispatch Thread
                    SwingUtilities.invokeLater(() -> gui.appendLog(n.formatAlert()));
                }
            }
            try { Thread.sleep(500); } catch (InterruptedException e) { running = false; }
        }
    }

    public void shutdown() { running = false; }
}


/* =========================================================
   APPOINTMENT SERVICE
   ========================================================= */

class AppointmentService {
    Map<String, Patient> patientMap = new LinkedHashMap<>();
    Map<String, Doctor> doctorMap = new LinkedHashMap<>();
    Map<String, Appointment> appointmentMap = new LinkedHashMap<>();
    Queue<Notification> notificationQueue;

    public AppointmentService(Queue<Notification> notificationQueue) { this.notificationQueue = notificationQueue; }

    public void registerPatient(Patient p) { patientMap.put(p.getId(), p); }
    public void registerDoctor(Doctor d) { doctorMap.put(d.doctorID, d); }

    public synchronized String bookAppointment(String appointmentID, String patientID, String doctorID) {
        Patient p = patientMap.get(patientID);
        if (p == null) throw new InvalidPatientIDException(patientID);
        Doctor d = doctorMap.get(doctorID);
        if (d == null) throw new InvalidPatientIDException(doctorID);
        if (appointmentMap.containsKey(appointmentID)) throw new DuplicateAppointmentException(appointmentID);

        String status;
        if (d.bookedAppointmentIds.size() < d.maxSlots) {
            status = "CONFIRMED";
            d.bookedAppointmentIds.add(appointmentID);
            notificationQueue.add(new AppointmentNotification(p.getName(), "Appointment " + appointmentID + " confirmed."));
        } else {
            status = "WAITLISTED";
        }
        appointmentMap.put(appointmentID, new Appointment(appointmentID, patientID, doctorID, status));
        return status;
    }

    public synchronized boolean cancelAppointment(String appointmentID) {
        Appointment a = appointmentMap.get(appointmentID);
        if (a == null) return false;
        a.status = "CANCELLED";
        Doctor d = doctorMap.get(a.doctorID);
        if (d != null) d.bookedAppointmentIds.remove(appointmentID);
        return true;
    }
}


/* =========================================================
   PHARMACY SERVICE
   ========================================================= */

class PharmacyService {
    Map<String, Medicine> inventory = new LinkedHashMap<>();
    Queue<Notification> notificationQueue;

    public PharmacyService(Queue<Notification> notificationQueue) { this.notificationQueue = notificationQueue; }

    public void addMedicine(String id, String name, int qty, int reorder) {
        inventory.put(id, new Medicine(id, name, qty, reorder));
    }

    public int dispenseMedicine(String medicineID, int requestedQuantity) {
        Medicine m = inventory.get(medicineID);
        if (m == null) throw new InvalidPatientIDException(medicineID);
        if (m.quantity < requestedQuantity) throw new OutOfStockException(medicineID, requestedQuantity, m.quantity);
        m.quantity -= requestedQuantity;
        if (m.quantity <= m.reorderLevel) {
            notificationQueue.add(new StockAlertNotification("Pharmacy Admin",
                    medicineID + " stock is low. Remaining: " + m.quantity));
        }
        return m.quantity;
    }
}


/* =========================================================
   SWING GUI  (this is the "applet-style" clickable window)
   ========================================================= */

public class HospitalGUI extends JFrame {

    Queue<Notification> notificationQueue = new LinkedList<>();
    AppointmentService appointmentService = new AppointmentService(notificationQueue);
    PharmacyService pharmacyService = new PharmacyService(notificationQueue);
    NotificationDispatchThread notifThread;

    JTextArea logArea = new JTextArea();
    DefaultTableModel appointmentTableModel;
    DefaultTableModel inventoryTableModel;
    JTable appointmentTable, inventoryTable;

    public HospitalGUI() {
        super("Smart Hospital Management System");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(950, 650);
        setLocationRelativeTo(null);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Patients & Doctors", buildRegistrationPanel());
        tabs.addTab("Appointments", buildAppointmentPanel());
        tabs.addTab("Pharmacy", buildPharmacyPanel());
        tabs.addTab("Notifications Log", buildLogPanel());

        add(tabs, BorderLayout.CENTER);

        JLabel status = new JLabel("  Smart Hospital System — notification thread running in background");
        status.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        add(status, BorderLayout.SOUTH);

        notifThread = new NotificationDispatchThread(notificationQueue, this);
        notifThread.start();
    }

    void appendLog(String text) {
        logArea.append(text + "\n");
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    /* ---------------- Patients & Doctors tab ---------------- */
    JPanel buildRegistrationPanel() {
        JPanel panel = new JPanel(new GridLayout(2, 1, 8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Patient registration
        JPanel patientPanel = new JPanel(new GridBagLayout());
        patientPanel.setBorder(BorderFactory.createTitledBorder("Register Patient"));
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(4, 4, 4, 4);
        gc.fill = GridBagConstraints.HORIZONTAL;

        JTextField pid = new JTextField(8);
        JTextField pname = new JTextField(12);
        JTextField page = new JTextField(4);
        JComboBox<String> ptype = new JComboBox<>(new String[]{"GENERAL", "SENIOR", "EMERGENCY"});
        JButton addPatientBtn = new JButton("Register Patient");

        int r = 0;
        addRow(patientPanel, gc, r++, "Patient ID:", pid);
        addRow(patientPanel, gc, r++, "Name:", pname);
        addRow(patientPanel, gc, r++, "Age:", page);
        addRow(patientPanel, gc, r++, "Type:", ptype);
        gc.gridx = 0; gc.gridy = r; gc.gridwidth = 2;
        patientPanel.add(addPatientBtn, gc);

        addPatientBtn.addActionListener(e -> {
            try {
                String id = pid.getText().trim();
                String name = pname.getText().trim();
                int age = Integer.parseInt(page.getText().trim());
                String type = (String) ptype.getSelectedItem();
                Patient p;
                switch (type) {
                    case "SENIOR": p = new SeniorPatient(id, name, age); break;
                    case "EMERGENCY": p = new EmergencyPatient(id, name, age); break;
                    default: p = new GeneralPatient(id, name, age);
                }
                appointmentService.registerPatient(p);
                appendLog("Patient registered: " + id + " (" + name + ", fee = Rs." + p.calculateFee() + ")");
                pid.setText(""); pname.setText(""); page.setText("");
                JOptionPane.showMessageDialog(this, "Patient registered successfully!\nConsultation fee: Rs." + p.calculateFee());
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Error: " + ex.getMessage(), "Invalid input", JOptionPane.ERROR_MESSAGE);
            }
        });

        // Doctor registration
        JPanel doctorPanel = new JPanel(new GridBagLayout());
        doctorPanel.setBorder(BorderFactory.createTitledBorder("Register Doctor"));
        GridBagConstraints gc2 = new GridBagConstraints();
        gc2.insets = new Insets(4, 4, 4, 4);
        gc2.fill = GridBagConstraints.HORIZONTAL;

        JTextField did = new JTextField(8);
        JTextField dname = new JTextField(12);
        JTextField dspec = new JTextField(12);
        JTextField dslots = new JTextField(4);
        JButton addDoctorBtn = new JButton("Register Doctor");

        int r2 = 0;
        addRow(doctorPanel, gc2, r2++, "Doctor ID:", did);
        addRow(doctorPanel, gc2, r2++, "Name:", dname);
        addRow(doctorPanel, gc2, r2++, "Specialisation:", dspec);
        addRow(doctorPanel, gc2, r2++, "Max Slots:", dslots);
        gc2.gridx = 0; gc2.gridy = r2; gc2.gridwidth = 2;
        doctorPanel.add(addDoctorBtn, gc2);

        addDoctorBtn.addActionListener(e -> {
            try {
                String id = did.getText().trim();
                String name = dname.getText().trim();
                String spec = dspec.getText().trim();
                int slots = Integer.parseInt(dslots.getText().trim());
                appointmentService.registerDoctor(new Doctor(id, name, spec, slots));
                appendLog("Doctor registered: " + id + " (" + name + ", " + spec + ", " + slots + " slots)");
                did.setText(""); dname.setText(""); dspec.setText(""); dslots.setText("");
                JOptionPane.showMessageDialog(this, "Doctor registered successfully!");
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Error: " + ex.getMessage(), "Invalid input", JOptionPane.ERROR_MESSAGE);
            }
        });

        panel.add(patientPanel);
        panel.add(doctorPanel);
        return panel;
    }

    /* ---------------- Appointments tab ---------------- */
    JPanel buildAppointmentPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createTitledBorder("Book / Cancel Appointment"));
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(4, 4, 4, 4);
        gc.fill = GridBagConstraints.HORIZONTAL;

        JTextField aid = new JTextField(8);
        JTextField apid = new JTextField(8);
        JTextField adid = new JTextField(8);
        JButton bookBtn = new JButton("Book Appointment");
        JButton cancelBtn = new JButton("Cancel Appointment");

        int r = 0;
        addRow(form, gc, r++, "Appointment ID:", aid);
        addRow(form, gc, r++, "Patient ID:", apid);
        addRow(form, gc, r++, "Doctor ID:", adid);
        gc.gridx = 0; gc.gridy = r; gc.gridwidth = 1;
        form.add(bookBtn, gc);
        gc.gridx = 1;
        form.add(cancelBtn, gc);

        appointmentTableModel = new DefaultTableModel(new Object[]{"Appt ID", "Patient", "Doctor", "Status"}, 0);
        appointmentTable = new JTable(appointmentTableModel);

        bookBtn.addActionListener(e -> {
            try {
                String status = appointmentService.bookAppointment(aid.getText().trim(), apid.getText().trim(), adid.getText().trim());
                Patient p = appointmentService.patientMap.get(apid.getText().trim());
                Doctor d = appointmentService.doctorMap.get(adid.getText().trim());
                appointmentTableModel.addRow(new Object[]{aid.getText().trim(), p.getName(), d.name, status});
                appendLog("Booking attempt: " + aid.getText().trim() + " -> " + status);
                JOptionPane.showMessageDialog(this, "Appointment " + status + "!");
                aid.setText(""); apid.setText(""); adid.setText("");
            } catch (RuntimeException ex) {
                appendLog("[CAUGHT] " + ex.getMessage());
                JOptionPane.showMessageDialog(this, ex.getMessage(), "Booking failed", JOptionPane.ERROR_MESSAGE);
            }
        });

        cancelBtn.addActionListener(e -> {
            boolean ok = appointmentService.cancelAppointment(aid.getText().trim());
            if (ok) {
                for (int i = 0; i < appointmentTableModel.getRowCount(); i++) {
                    if (appointmentTableModel.getValueAt(i, 0).equals(aid.getText().trim())) {
                        appointmentTableModel.setValueAt("CANCELLED", i, 3);
                    }
                }
                appendLog("Appointment cancelled: " + aid.getText().trim());
                JOptionPane.showMessageDialog(this, "Appointment cancelled.");
            } else {
                JOptionPane.showMessageDialog(this, "Appointment not found.", "Error", JOptionPane.ERROR_MESSAGE);
            }
        });

        panel.add(form, BorderLayout.NORTH);
        panel.add(new JScrollPane(appointmentTable), BorderLayout.CENTER);
        return panel;
    }

    /* ---------------- Pharmacy tab ---------------- */
    JPanel buildPharmacyPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createTitledBorder("Add / Dispense Medicine"));
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(4, 4, 4, 4);
        gc.fill = GridBagConstraints.HORIZONTAL;

        JTextField mid = new JTextField(8);
        JTextField mname = new JTextField(12);
        JTextField mqty = new JTextField(5);
        JTextField mreorder = new JTextField(5);
        JButton addBtn = new JButton("Add Medicine");

        JTextField dispId = new JTextField(8);
        JTextField dispQty = new JTextField(5);
        JButton dispBtn = new JButton("Dispense");

        int r = 0;
        addRow(form, gc, r++, "Medicine ID:", mid);
        addRow(form, gc, r++, "Name:", mname);
        addRow(form, gc, r++, "Quantity:", mqty);
        addRow(form, gc, r++, "Reorder Level:", mreorder);
        gc.gridx = 0; gc.gridy = r++; gc.gridwidth = 2;
        form.add(addBtn, gc);
        gc.gridwidth = 1;

        addRow(form, gc, r++, "Dispense — Medicine ID:", dispId);
        addRow(form, gc, r++, "Dispense — Quantity:", dispQty);
        gc.gridx = 0; gc.gridy = r; gc.gridwidth = 2;
        form.add(dispBtn, gc);

        inventoryTableModel = new DefaultTableModel(new Object[]{"ID", "Medicine", "Stock", "Reorder Level"}, 0);
        inventoryTable = new JTable(inventoryTableModel);

        addBtn.addActionListener(e -> {
            try {
                String id = mid.getText().trim();
                String name = mname.getText().trim();
                int qty = Integer.parseInt(mqty.getText().trim());
                int reorder = Integer.parseInt(mreorder.getText().trim());
                pharmacyService.addMedicine(id, name, qty, reorder);
                inventoryTableModel.addRow(new Object[]{id, name, qty, reorder});
                appendLog("Medicine added: " + id + " (" + name + ", stock=" + qty + ")");
                mid.setText(""); mname.setText(""); mqty.setText(""); mreorder.setText("");
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Error: " + ex.getMessage(), "Invalid input", JOptionPane.ERROR_MESSAGE);
            }
        });

        dispBtn.addActionListener(e -> {
            try {
                String id = dispId.getText().trim();
                int qty = Integer.parseInt(dispQty.getText().trim());
                int remaining = pharmacyService.dispenseMedicine(id, qty);
                for (int i = 0; i < inventoryTableModel.getRowCount(); i++) {
                    if (inventoryTableModel.getValueAt(i, 0).equals(id)) {
                        inventoryTableModel.setValueAt(remaining, i, 2);
                    }
                }
                appendLog("Dispensed " + qty + " units of " + id + ". Remaining: " + remaining);
                JOptionPane.showMessageDialog(this, "Dispensed! Remaining stock: " + remaining);
                dispId.setText(""); dispQty.setText("");
            } catch (RuntimeException ex) {
                appendLog("[CAUGHT] " + ex.getMessage());
                JOptionPane.showMessageDialog(this, ex.getMessage(), "Dispense failed", JOptionPane.ERROR_MESSAGE);
            }
        });

        panel.add(form, BorderLayout.NORTH);
        panel.add(new JScrollPane(inventoryTable), BorderLayout.CENTER);
        return panel;
    }

    /* ---------------- Notifications log tab ---------------- */
    JPanel buildLogPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        panel.add(new JScrollPane(logArea), BorderLayout.CENTER);
        return panel;
    }

    /* ---------------- helper ---------------- */
    void addRow(JPanel panel, GridBagConstraints gc, int row, String label, JComponent field) {
        gc.gridx = 0; gc.gridy = row; gc.gridwidth = 1;
        panel.add(new JLabel(label), gc);
        gc.gridx = 1;
        panel.add(field, gc);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {}
            new HospitalGUI().setVisible(true);
        });
    }
}
